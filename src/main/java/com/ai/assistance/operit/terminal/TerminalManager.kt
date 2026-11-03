package com.ai.assistance.operit.terminal

import android.content.Context
import android.system.Os
import com.ai.assistance.operit.terminal.command.CommandCancellationOutcome
import com.ai.assistance.operit.terminal.command.CommandCancellationRecovery
import com.ai.assistance.operit.terminal.process.awaitTerminalExit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.launch
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.ai.assistance.operit.terminal.maintenance.UbuntuRemovalJournal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.QueuedCommand
import com.ai.assistance.operit.terminal.view.domain.OutputProcessor
import java.util.UUID
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.utils.SourceManager
import com.ai.assistance.operit.terminal.utils.SSHConfigManager
import com.ai.assistance.operit.terminal.utils.SSHDServerManager
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.LocalFileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.PRootBindMount
import com.ai.assistance.operit.terminal.provider.filesystem.PRootMountMapping
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.provider.type.TerminalProvider
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import com.ai.assistance.operit.terminal.provider.type.LocalTerminalProvider
import com.ai.assistance.operit.terminal.provider.type.SSHTerminalProvider
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator

class TerminalManager private constructor(
    private val context: Context
) {
    internal val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val commandEventChannel = Channel<CommandExecutionEvent>(Channel.UNLIMITED)
    private data class PendingInput(
        val text: String,
        val commandId: String? = null,
        val recovery: CommandCancellationRecovery? = null,
        val recoveryProbe: Boolean = false,
    )
    private val sessionInputs = ConcurrentHashMap<String, Channel<PendingInput>>()
    private val inputJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val sessionPtys = ConcurrentHashMap<String, Pty>()
    private val envInitMutex = Mutex()
    private val sessionStartMutex = Mutex()
    private var isEnvInitialized = false

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    private val closingSessions = ConcurrentHashMap.newKeySet<String>()
    private val sessionProcesses = ConcurrentHashMap<String, Process>()
    private val sessionStartups = ConcurrentHashMap<String, CompletableDeferred<Process?>>()
    private val processReapJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    
    // SharedPreferences for reading settings
    private val prefs = context.getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)

    // 核心组件
    private val sessionManager = SessionManager(this)
    private val outputProcessor = OutputProcessor(
        onCommandExecutionEvent = { event ->
            // Serialize events before exposing them to collectors.
            if (!commandEventChannel.trySend(event).isSuccess) {
                Log.w(TAG, "Dropping terminal command event for session ${event.sessionId}")
            }
        },
        onDirectoryChangeEvent = { event ->
            coroutineScope.launch {
                _directoryChangeEvents.emit(event)
            }
        },
        onShellPrompt = { sessionId -> enqueueCancellationProbe(sessionId) },
        onCommandCompleted = { sessionId ->
            coroutineScope.launch {
                processNextQueuedCommand(sessionId)
            }
        }
    )
    private val sourceManager = SourceManager(context)
    private val sshConfigManager = SSHConfigManager(context)
    private val sshdServerManager = SSHDServerManager.getInstance(context)
    
    // 单例的 TerminalProvider
    private var terminalProvider: TerminalProvider? = null
    private val providerMutex = Mutex()

    // 状态和事件流
    private val _commandExecutionEvents = MutableSharedFlow<CommandExecutionEvent>()
    val commandExecutionEvents: SharedFlow<CommandExecutionEvent> = _commandExecutionEvents.asSharedFlow()

    private val _directoryChangeEvents = MutableSharedFlow<SessionDirectoryEvent>()
    val directoryChangeEvents: SharedFlow<SessionDirectoryEvent> = _directoryChangeEvents.asSharedFlow()

    // 暴露会话管理器的状态
    val terminalState: StateFlow<TerminalState> = sessionManager.state

    // 为了向后兼容，提供单独的状态流
    val sessions = terminalState.map { it.sessions }
    val currentSessionId = terminalState.map { it.currentSessionId }
    val currentDirectory = terminalState.map { it.currentSession?.currentDirectory ?: "$ " }
    val isInteractiveMode = terminalState.map { it.currentSession?.isInteractiveMode ?: false }
    val interactivePrompt = terminalState.map { it.currentSession?.interactivePrompt ?: "" }
    val isFullscreen = terminalState.map { it.currentSession?.isFullscreen ?: false }
    val terminalEmulator = terminalState.map { it.currentSession?.ansiParser ?: AnsiTerminalEmulator() }

    companion object {
        @Volatile
        private var INSTANCE: TerminalManager? = null

        private val environmentMaintenanceMutex = Mutex()
        @Volatile
        private var environmentMaintenanceInProgress = false
        private val _environmentMaintenanceState = MutableStateFlow(false)
        val environmentMaintenanceState: StateFlow<Boolean> = _environmentMaintenanceState.asStateFlow()

        fun isEnvironmentRemovalPending(context: Context): Boolean =
            UbuntuRemovalJournal(context.filesDir).isPending()

        fun getInstance(context: Context): TerminalManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** 停止会话并重置初始化状态，宿主在此期间删除终端环境及安装资源。 */
        suspend fun withEnvironmentMaintenance(
            context: Context,
            resumeOnly: Boolean = false,
            operation: suspend () -> Unit,
        ) {
            environmentMaintenanceMutex.withLock {
                val journal = UbuntuRemovalJournal(context.filesDir)
                // 自动恢复在互斥锁内复查，避免已结束的恢复请求删除后来重新导入的环境。
                if (resumeOnly && !journal.isPending()) return@withLock
                val manager = synchronized(this) {
                    environmentMaintenanceInProgress = true
                    _environmentMaintenanceState.value = true
                    INSTANCE
                }
                try {
                    // 删除开始后完整执行，避免退出资源页面留下半删除的系统。
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        val remove: suspend () -> Unit = {
                            journal.begin()
                            operation()
                            // 清理失败时保留记录，下次启动继续；只有完整成功才清除。
                            journal.finish()
                        }
                        if (manager == null) {
                            remove()
                        } else {
                            manager.envInitMutex.withLock {
                                manager.sessionStartMutex.withLock {
                                    manager.providerMutex.withLock {
                                        manager.prepareForMaintenance()
                                    }
                                    manager.isEnvInitialized = false
                                    remove()
                                }
                            }
                        }
                    }
                } finally {
                    environmentMaintenanceInProgress = false
                    _environmentMaintenanceState.value = false
                }
            }
        }

        @Volatile
        private var ubuntuArchivePreparer: (suspend (Context, File) -> Unit)? = null

        /** 宿主提供并校验 Ubuntu 安装归档，注册过程不创建默认会话。 */
        fun setUbuntuArchivePreparer(preparer: suspend (Context, File) -> Unit) {
            ubuntuArchivePreparer = preparer
        }

        @Volatile
        private var localSessionInitializer: (Context) -> String = { ":" }

        /** 宿主在创建终端前注册本地会话初始化命令；命令失败时不发送就绪标记。 */
        fun setLocalSessionInitializer(initializer: (Context) -> String) {
            localSessionInitializer = initializer
        }

        private const val TAG = "TerminalManager"
        private const val UBUNTU_FILENAME = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        private const val MAX_HISTORY_ITEMS = 500
        private const val MAX_OUTPUT_LINES_PER_ITEM = 1000
        private const val TERMINAL_ENTER = "\r"
        private const val COMMAND_COMPLETION_MARKER_PREFIX = "__OPERIT_COMMAND_DONE__:"
    }

    init {
        coroutineScope.launch {
            for (event in commandEventChannel) {
                _commandExecutionEvents.emit(event)
            }
        }
        // 在初始化时异步创建默认session
        coroutineScope.launch {
            try {
                Log.d(TAG, "Creating default session...")
                // 自动检测：如果有SSH配置则创建SSH会话，否则创建本地会话
                val sshConfig = sshConfigManager.getConfig()
                val isEnabled = sshConfigManager.isEnabled()
                if (sshConfig != null && isEnabled) {
                    Log.d(TAG, "Found SSH config, creating SSH session")
                    createNewSession("SSH")
                } else {
                    Log.d(TAG, "No SSH config, creating local session")
                    createNewSession("Local")
                }
                Log.d(TAG, "Default session created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create default session", e)
            }
        }
    }

    /**
     * 创建新会话 - 同步等待初始化完成
     * 自动检测终端类型：如果配置了SSH则使用SSH，否则使用本地终端
     * 
     * @param title 会话标题
     */
    suspend fun createNewSession(
        title: String? = null,
        automation: Boolean = false,
    ): TerminalSessionData {
        val terminalType = if (sshConfigManager.getConfig() != null && sshConfigManager.isEnabled())
            TerminalType.SSH else TerminalType.LOCAL
        val startup = CompletableDeferred<Process?>()
        val created = synchronized(Companion) {
            check(!environmentMaintenanceInProgress && !isEnvironmentRemovalPending(context)) {
                "Ubuntu removal is incomplete; finish cleanup in Offline resources"
            }
            sessionManager.createNewSession(title, terminalType, automation).also {
                sessionStartups[it.id] = startup
            }
        }
        initializeSession(created.id, startup)
        try {
            val ready = withTimeoutOrNull(30_000L) {
                terminalState.first { state ->
                    val current = state.sessions.find { it.id == created.id }
                    current == null || current.initState == SessionInitState.READY
                }
            }
            checkNotNull(ready) { "Session initialization timeout" }
            return checkNotNull(sessionManager.getSession(created.id)) {
                "Session closed during initialization"
            }
        } catch (cancelled: CancellationException) {
            closeSessionAndAwait(created.id, 3_000L)
            throw cancelled
        } catch (error: Exception) {
            closeSessionAndAwait(created.id, 3_000L)
            throw error
        }
    }

    /**
     * 切换到会话
     */
    fun switchToSession(sessionId: String) {
        sessionManager.switchToSession(sessionId)
    }

    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    /** 等待已发布或仍在启动的原进程退出，不把移出状态表当作进程终止证明。 */
    suspend fun closeSessionAndAwait(sessionId: String, timeoutMs: Long): Boolean {
        val startup = sessionStartups[sessionId]
        val published = sessionProcesses[sessionId]
        if (!sessionManager.closeSession(sessionId)) closeTerminalSession(sessionId)
        return withContext(Dispatchers.IO + NonCancellable) {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                val process = published ?: startup?.await()
                while (process?.isAlive == true) delay(25L)
                if (process != null) sessionProcesses.remove(sessionId, process)
                true
            } == true
        }
    }

    internal fun finishClosedSession(session: TerminalSessionData) {
        outputProcessor.finishClosedSession(session, context.getString(R.string.terminal_session_closed))
    }

    /** 排队任务只取消自己；运行任务先停止队列推进，再发中断和限时清理。 */
    suspend fun cancelCommand(
        sessionId: String,
        commandId: String,
        settleMs: Long = 3_000L,
    ): CommandCancellationOutcome = withContext(Dispatchers.IO + NonCancellable) {
        val original = sessionManager.getSession(sessionId)
            ?: return@withContext CommandCancellationOutcome.ALREADY_FINISHED
        val queued = synchronized(original.commandLifecycle) {
            val current = sessionManager.getSession(sessionId)
            current?.commandQueue?.removeAll { it.id == commandId } == true
        }
        if (queued) {
            commandEventChannel.trySend(CommandExecutionEvent(
                commandId, sessionId, "", true, terminationReason = "cancelled",
            ))
            return@withContext CommandCancellationOutcome.REMOVED_FROM_QUEUE
        }
        original.commandLifecycle.cancellationMutex.withLock {
            val running = synchronized(original.commandLifecycle) {
                val current = sessionManager.getSession(sessionId)
                val command = current?.currentExecutingCommand
                if (current == null || current.commandLifecycle.closed ||
                    command == null || command.id != commandId || !command.isExecuting) false
                else {
                    current.commandLifecycle.cancellingCommandId = commandId
                    val recovery = CommandCancellationRecovery(commandId)
                    current.commandLifecycle.cancellationRecovery = recovery
                    enqueueInput(current, "\u0003", commandId, recovery)
                    true
                }
            }
            if (!running) return@withLock CommandCancellationOutcome.ALREADY_FINISHED
            val settled = withTimeoutOrNull(settleMs.coerceAtLeast(1L)) {
                while (true) {
                    val done = synchronized(original.commandLifecycle) {
                        val current = sessionManager.getSession(sessionId)?.currentExecutingCommand
                        current == null || current.id != commandId || !current.isExecuting
                    }
                    if (done) break
                    delay(25L)
                }
                true
            } == true
            // 限时结束与完成事件可能同时到达，退役前在同一监视器内复查。
            val finished = synchronized(original.commandLifecycle) {
                val current = sessionManager.getSession(sessionId)?.currentExecutingCommand
                current == null || current.id != commandId || !current.isExecuting
            }
            if (settled || finished) {
                synchronized(original.commandLifecycle) {
                    original.commandLifecycle.cancellingCommandId = null
                    original.commandLifecycle.cancellationRecovery = null
                }
                processNextQueuedCommand(sessionId)
                if (original.commandLifecycle.closed) {
                    if (closeSessionAndAwait(sessionId, 2_000L)) CommandCancellationOutcome.SESSION_CLOSED
                    else CommandCancellationOutcome.CLEANUP_INCOMPLETE
                } else CommandCancellationOutcome.INTERRUPTED
            } else {
                val pty = sessionManager.getSession(sessionId)?.pty
                val cleaned = try {
                    pty?.terminateSession(2_000L) == true
                } catch (error: Exception) {
                    Log.w(TAG, "Session process cleanup failed: $sessionId", error)
                    false
                }
                val closed = closeSessionAndAwait(sessionId, 2_000L)
                if (cleaned && closed) CommandCancellationOutcome.SESSION_CLOSED
                else CommandCancellationOutcome.CLEANUP_INCOMPLETE
            }
        }
    }

    /**
     * 会话关闭后的运行态清理（非持久化状态）。
     */
    fun onSessionClosed(sessionId: String) {
        outputProcessor.clearSessionState(sessionId)
    }
    
    /**
     * 保存会话的滚动位置
     */
    fun saveScrollOffset(sessionId: String, scrollOffset: Float) {
        sessionManager.saveScrollOffset(sessionId, scrollOffset)
    }
    
    /**
     * 获取会话的滚动位置
     */
    fun getScrollOffset(sessionId: String): Float {
        return sessionManager.getScrollOffset(sessionId)
    }

    private fun createBusyboxSymlinks() {
        val links = listOf(
            "awk", "ash", "basename", "bzip2", "curl", "cp", "chmod", "cut", "cat", "du", "dd",
            "find", "grep", "gzip", "hexdump", "head", "id", "lscpu", "mkdir", "realpath", "rm",
            "sed", "stat", "sh", "tr", "tar", "uname", "xargs", "xz", "xxd"
        )
        val busybox = File(binDir, "busybox")
        for (linkName in links) {
            try {
                createSymbolicLink(busybox, linkName, binDir, true)
                Log.d(TAG, "Created busybox link for '$linkName'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create link for '$linkName'", e)
            }
        }
        try {
            val fileLink = File(binDir, "file")
            if (!fileLink.exists()) {
                Files.createSymbolicLink(fileLink.toPath(), File("/system/bin/file").toPath())
                Log.d(TAG, "Created symlink for 'file'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create symlink for 'file'", e)
        }
    }

    private fun writeInputToKernel(session: TerminalSessionData, input: String, source: String) {
        val writer = checkNotNull(session.sessionWriter) { "Terminal writer is unavailable" }
        writer.write(input)
        writer.flush()
        Log.d(
            TAG,
            "Sent terminal input to kernel [source=$source, sessionId=${session.id}]: '${escapeInputForLog(input)}'"
        )
    }

    private fun escapeInputForLog(input: String): String {
        return buildString(input.length) {
            input.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.isISOControl()) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }
    }

    /**
     * 发送命令
     */
    suspend fun sendCommand(command: String, commandId: String? = null): String {
        val actualId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getCurrentSession() ?: return actualId
        if (session.isInteractiveMode || session.initState != SessionInitState.READY) {
            sendInput(command + TERMINAL_ENTER, session.id)
            return actualId
        }
        return sendCommandToSession(session.id, command, actualId)
    }

    suspend fun sendCommandToSession(sessionId: String, command: String, commandId: String? = null): String {
        val actualId = commandId ?: UUID.randomUUID().toString()
        val original = sessionManager.getSession(sessionId)
        if (original == null) {
            emitCommandFailureEvent(actualId, sessionId, "Terminal session does not exist: $sessionId")
            return actualId
        }
        synchronized(original.commandLifecycle) {
            val current = sessionManager.getSession(sessionId)
            if (current == null || current.commandLifecycle.closed) {
                emitCommandFailureEvent(actualId, sessionId, "Terminal session is closed")
            } else if (current.initState != SessionInitState.READY) {
                emitCommandFailureEvent(actualId, sessionId, "Terminal session is not ready: ${current.initState}")
            } else if (current.currentExecutingCommand?.isExecuting == true ||
                current.commandLifecycle.cancellingCommandId != null) {
                current.commandQueue.add(QueuedCommand(actualId, command))
            } else {
                executeCommandInternal(command, current, actualId)
            }
        }
        return actualId
    }

    private fun emitCommandFailureEvent(
        commandId: String,
        sessionId: String,
        message: String
    ) {
        if (!commandEventChannel.trySend(
                CommandExecutionEvent(
                    commandId = commandId,
                    sessionId = sessionId,
                    outputChunk = message,
                    isCompleted = true,
                    exitCode = -1,
                    terminationReason = "dispatch_failed",
                )
            ).isSuccess
        ) {
            Log.w(TAG, "Dropping terminal command failure event for session $sessionId")
        }
    }

    /**
     * 处理队列中的下一个命令
     */
    private suspend fun processNextQueuedCommand(sessionId: String) {
        val original = sessionManager.getSession(sessionId) ?: return
        synchronized(original.commandLifecycle) {
            val current = sessionManager.getSession(sessionId) ?: return
            if (current.commandLifecycle.closed || current.commandLifecycle.cancellingCommandId != null ||
                current.currentExecutingCommand?.isExecuting == true) return
            if (current.commandQueue.isNotEmpty()) {
                val next = current.commandQueue.removeAt(0)
                executeCommandInternal(next.command, current, next.id)
            }
        }
    }

    /** 命令提交、完成和取消共用生命周期监视器，避免取消误伤下一条命令。 */
    private fun executeCommandInternal(command: String, session: TerminalSessionData, commandId: String) {
        handleRegularCommand(command, session, commandId)
        session.commandLifecycle.latestScreen = null
        if (!enqueueInput(session, buildCommandInput(command, commandId), commandId)) {
            sessionManager.closeSession(session.id)
        }
    }

    /** 每个会话独立串行写入；阻塞的 PTY 不占生命周期锁，也不阻塞其他会话。 */
    private fun startInputWorker(sessionId: String) {
        val inputs = Channel<PendingInput>(Channel.UNLIMITED)
        sessionInputs[sessionId] = inputs
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            try {
                for (input in inputs) {
                    val session = sessionManager.getSession(sessionId) ?: break
                    val accepted = synchronized(session.commandLifecycle) {
                        if (session.commandLifecycle.closed || (input.commandId != null &&
                                session.currentExecutingCommand?.id != input.commandId)) {
                            false
                        } else if (input.recovery != null) {
                            if (session.commandLifecycle.cancellationRecovery !== input.recovery) false
                            else {
                                // 写操作仍在锁外；先标记发送阶段，避免漏掉紧随中断到来的提示符。
                                if (input.recoveryProbe) input.recovery.onProbeDispatched()
                                else input.recovery.onInterruptDispatched()
                                true
                            }
                        } else true
                    }
                    if (!accepted) continue
                    try {
                        writeInputToKernel(session, input.text, "session-input")
                        if (session.isWaitingForInteractiveInput) {
                            sessionManager.updateSession(sessionId) { it.copy(isWaitingForInteractiveInput = false) }
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Terminal input failed: $sessionId", error)
                        sessionManager.closeSession(sessionId)
                        break
                    }
                }
            } finally {
                inputs.cancel()
                sessionInputs.remove(sessionId, inputs)
            }
        }
        inputJobs[sessionId] = job
        job.invokeOnCompletion { inputJobs.remove(sessionId, job) }
        job.start()
    }

    private fun enqueueInput(
        session: TerminalSessionData,
        text: String,
        commandId: String? = null,
        recovery: CommandCancellationRecovery? = null,
        recoveryProbe: Boolean = false,
    ): Boolean = sessionInputs[session.id]?.trySend(
        PendingInput(text, commandId, recovery, recoveryProbe),
    )?.isSuccess == true

    /** 提示符只触发验证，不直接结束命令；令牌回复才证明 shell 已重新处理输入。 */
    private fun enqueueCancellationProbe(sessionId: String) {
        val session = sessionManager.getSession(sessionId) ?: return
        synchronized(session.commandLifecycle) {
            val recovery = session.commandLifecycle.cancellationRecovery ?: return
            if (session.commandLifecycle.closed || session.terminalType != TerminalType.LOCAL ||
                session.currentExecutingCommand?.id != recovery.commandId ||
                session.currentExecutingCommand?.isExecuting != true ||
                !recovery.queueProbeAfterPrompt()) return
            if (!enqueueInput(session, recovery.probeInput, recovery.commandId, recovery, recoveryProbe = true)) {
                sessionManager.closeSession(sessionId)
            }
        }
    }

    private fun buildCommandInput(command: String, commandId: String): String {
        val normalizedCommand = command.trimEnd('\r', '\n')
        return buildString {
            append(normalizedCommand)
            append(TERMINAL_ENTER)
            append("printf '\\n%s:%s\\n' '")
            append(COMMAND_COMPLETION_MARKER_PREFIX)
            append(commandId)
            append("' " )
            append('$').append('?')
            append(TERMINAL_ENTER)
        }
    }

    /**
     * 发送输入
     */
    fun sendInput(input: String, sessionId: String? = sessionManager.getCurrentSession()?.id) {
        val session = sessionId?.let { sessionManager.getSession(it) } ?: return
        // 虚拟按键同样遵守全屏程序启用的应用光标键模式，已编码的修饰序列保持原样。
        val text = if (session.ansiParser.isApplicationCursorKeysEnabled() &&
            input.length == 3 && input.startsWith("\u001b[") && input.last() in "ABCDHF"
        ) "\u001bO${input.last()}" else input
        if (!enqueueInput(session, text)) {
            Log.w(TAG, "Terminal input queue is closed for session ${session.id}")
        }
    }

    /**
     * 发送中断信号
     */
    fun sendInterruptSignal(sessionId: String? = sessionManager.getCurrentSession()?.id) {
        val session = sessionId?.let { sessionManager.getSession(it) } ?: return
        synchronized(session.commandLifecycle) {
            enqueueInput(session, "\u0003", session.currentExecutingCommand?.id)
        }
    }

    private fun initializeSession(sessionId: String, startup: CompletableDeferred<Process?>) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                check(initializeEnvironment()) { "Terminal environment initialization failed" }
                sessionStartMutex.withLock {
                    if (environmentMaintenanceInProgress || isEnvironmentRemovalPending(context)) {
                        error("Terminal environment is unavailable")
                    }
                    val original = sessionManager.getSession(sessionId) ?: return@withLock
                    val provider = getTerminalProvider()
                    val result = if (original.automation && provider is LocalTerminalProvider)
                        provider.startAutomationSession(sessionId) else provider.startSession(sessionId)
                    val (terminalSession, pty) = result.getOrThrow()
                    sessionPtys[sessionId] = pty
                    sessionProcesses[sessionId] = terminalSession.process
                    activeSessions[sessionId] = terminalSession
                    startup.complete(terminalSession.process)
                    if (sessionManager.getSession(sessionId) == null || original.commandLifecycle.closed) {
                        closeTerminalSession(sessionId)
                        return@withLock
                    }
                    val writer = terminalSession.stdin.writer()
                    val reader = coroutineScope.launch(start = CoroutineStart.LAZY) {
                        var stopped = false
                        var failure: Exception? = null
                        try {
                            terminalSession.stdout.reader(Charsets.UTF_8).use { input ->
                                val buffer = CharArray(4096)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count > 0) outputProcessor.processOutput(sessionId, String(buffer, 0, count), sessionManager)
                                }
                                stopped = true
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failure = error
                        } finally {
                            if (!closingSessions.contains(sessionId) && !original.commandLifecycle.closed &&
                                (stopped || failure != null || !terminalSession.process.isAlive)) {
                                withContext(NonCancellable) {
                                    withTimeoutOrNull(500L) {
                                        while (terminalSession.process.isAlive) delay(25L)
                                    }
                                    failure?.let { Log.w(TAG, "Terminal reader stopped: $sessionId", it) }
                                    handleTerminalSessionExit(sessionId, terminalSession)
                                }
                            }
                        }
                    }
                    val exit = coroutineScope.launch(start = CoroutineStart.LAZY) {
                        awaitTerminalExit({ terminalSession.process.isAlive }, reader)
                        withContext(NonCancellable) {
                            handleTerminalSessionExit(sessionId, terminalSession)
                            reader.cancel()
                        }
                    }
                    reader.invokeOnCompletion { closingSessions.remove(sessionId) }
                    sessionManager.updateSession(sessionId) {
                        it.copy(terminalSession = terminalSession, pty = pty, sessionWriter = writer,
                            readJob = reader, exitJob = exit)
                    }
                    if (sessionManager.getSession(sessionId) == null || original.commandLifecycle.closed) {
                        reader.cancel()
                        exit.cancel()
                        closeTerminalSession(sessionId)
                    } else {
                        startInputWorker(sessionId)
                        reader.start()
                        exit.start()
                    }
                }
            } catch (cancelled: CancellationException) {
                sessionManager.closeSession(sessionId)
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Error starting session: $sessionId", error)
                sessionManager.closeSession(sessionId)
                closeTerminalSession(sessionId)
            } finally {
                startup.complete(sessionProcesses[sessionId])
                sessionStartups.remove(sessionId, startup)
            }
        }
    }

    private fun handleTerminalSessionExit(sessionId: String, terminalSession: TerminalSession) {
        if (sessionManager.getSession(sessionId) == null) return
        val code = if (terminalSession.process.isAlive) -1 else try {
            terminalSession.process.waitFor()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to observe terminal exit status: $sessionId", error)
            -1
        }
        outputProcessor.handleSessionExit(sessionId,
            if (code < 0) context.getString(R.string.terminal_session_closed)
            else context.getString(R.string.terminal_exited_with_code, code), sessionManager, code)
        sessionManager.closeSession(sessionId)
    }

    /**
     * 获取或创建单例的终端提供者
     */
    private suspend fun getTerminalProvider(): TerminalProvider {
        providerMutex.withLock {
            check(!environmentMaintenanceInProgress && !isEnvironmentRemovalPending(context)) {
                "Ubuntu removal is incomplete; finish cleanup in Offline resources"
            }
            if (terminalProvider == null) {
                val sshConfig = sshConfigManager.getConfig()
                val provider = if (sshConfig != null && sshConfigManager.isEnabled()) {
                    Log.d(TAG, "Creating singleton SSH terminal provider")
                    SSHTerminalProvider(context, sshConfig, this)
                } else {
                    Log.d(TAG, "Creating singleton local terminal provider")
                    LocalTerminalProvider(context)
                }
                provider.connect().getOrThrow()
                terminalProvider = provider
            }
            return terminalProvider!!
        }
    }

    suspend fun initializeEnvironment(): Boolean = envInitMutex.withLock {
        if (environmentMaintenanceInProgress || isEnvironmentRemovalPending(context)) return@withLock false
        withContext(Dispatchers.IO) {
            try {
                if (!isEnvInitialized) {
                    Log.d(TAG, "Starting environment initialization...")
                    createDirectories()
                    linkNativeLibs()
                    createBusyboxSymlinks()
                    extractAssets()
                }
                writeStartScriptAtomically()
                isEnvInitialized = true
                Log.d(TAG, "Environment initialization completed successfully.")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Environment initialization failed", e)
                false
            }
        }
    }

    private fun createDirectories() {
        if (!usrDir.exists()) {
            usrDir.mkdirs()
        }
        if (!binDir.exists()) {
            binDir.mkdirs()
            Log.d(TAG, "Created bin directory at: ${binDir.absolutePath}")
        }
        File(filesDir, "tmp").mkdirs()
    }

    private fun linkNativeLibs() {
        Log.d(TAG, "Linking native libraries from: $nativeLibDir")

        val nativeLibDirFile = File(nativeLibDir)
        if (!nativeLibDirFile.exists() || !nativeLibDirFile.isDirectory) {
            Log.e(TAG, "Native library directory not found or is not a directory.")
            return
        }

        Log.d(TAG, "Native lib directory contents:")
        nativeLibDirFile.listFiles()?.forEach { file ->
            Log.d(TAG, "  - ${file.name} (file ${file.length()} bytes)")
        }

        val busybox = File(binDir, "busybox")

        // First, we need to link busybox itself so we can use it.
        val busyboxSo = File(nativeLibDir, "libbusybox.so")
        Log.d(TAG, "Checking busybox: libbusybox.so exists = ${busyboxSo.exists()}, busybox exists = ${busybox.exists()}")

        if (!busyboxSo.exists()) {
            Log.e(TAG, "libbusybox.so not found, cannot create busybox link")
            return
        }

        // Always ensure proper busybox link - remove any existing file/broken link first
        try {
            val link = busybox.toPath()
            val target = busyboxSo.toPath()

            // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
            Files.deleteIfExists(link)

            // CRITICAL: Set execute permission on the target .so file before creating symlink
            busyboxSo.setExecutable(true, false)

            // Create the symbolic link
            Files.createSymbolicLink(link, target)
            Log.d(TAG, "Created busybox symbolic link using Java NIO")

            // Verify the link was created successfully and is functional
            if (busybox.exists() && busybox.canExecute()) {
                Log.d(TAG, "Verification: busybox link exists and is executable at ${busybox.absolutePath}")
            } else {
                Log.e(TAG, "Verification failed: busybox link not functional after creation")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create busybox link using Java NIO", e)
            return
        }

        try {
            Files.deleteIfExists(File(binDir, "libtalloc.so.2").toPath())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove stale libtalloc link", e)
        }

        // Symlink other binaries
        val libraries = mapOf(
            "liboperit_proot.so" to "proot",
            "liboperit_loader.so" to "loader",
            "libbash.so" to "bash",
            "libsudo.so" to "sudo"
        )

        libraries.forEach { (libName, linkName) ->
            val libFile = File(nativeLibDir, libName)
            val linkFile = File(binDir, linkName)

            Log.d(TAG, "Checking $libName at ${libFile.absolutePath}, exists: ${libFile.exists()}")

            if (!libFile.exists()) {
                Log.w(TAG, "Native library not found: $libName")
                return@forEach
            }

            // Always ensure proper link - remove any existing file/broken link first
            try {
                val link = linkFile.toPath()
                val target = libFile.toPath()

                // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
                Files.deleteIfExists(link)

                // CRITICAL: Set execute permission on the target .so file before creating symlink
                libFile.setExecutable(true, false)

                // Create the symbolic link
                Files.createSymbolicLink(link, target)
                Log.d(TAG, "Created $linkName symbolic link using Java NIO")

                // Verify the link was created successfully and is executable
                if (linkFile.exists() && linkFile.canExecute()) {
                    Log.d(TAG, "Verification: $linkName link exists and is executable at ${linkFile.absolutePath}")
                } else {
                    Log.w(TAG, "Verification failed: $linkName link not executable after creation")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create $linkName link using Java NIO", e)
            }
        }
    }

    @Throws(IOException::class)
    private fun createSymbolicLink(target: File, linkName: String, linkDir: File, force: Boolean) {
        val linkFile = File(linkDir, linkName)

        // Use relative path for target if it's in the same directory
        val targetPath = if (target.parentFile == linkDir) {
            Paths.get(target.name)
        } else {
            target.toPath()
        }

        if (force) {
            Files.deleteIfExists(linkFile.toPath())
        }
        Files.createSymbolicLink(linkFile.toPath(), targetPath)
    }

    private suspend fun extractAssets() {
        val rootfs = File(filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
        val installed = File(rootfs, ".operit_installed_ok").isFile || File(rootfs, "etc/issue.net").isFile
        if (!installed) {
            val preparer = checkNotNull(ubuntuArchivePreparer) { "Ubuntu archive preparer is not registered" }
            preparer(context, File(filesDir, UBUNTU_FILENAME))
        }

        // 每次更新小型脚本；大型安装归档由宿主校验后提供。
        val script = context.assets.open("setup_fake_sysdata.sh").bufferedReader(Charsets.UTF_8).use { it.readText() }
        File(filesDir, "setup_fake_sysdata.sh").writeText(
            script.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n"),
            Charsets.UTF_8,
        )
    }

    private fun generateStartScript(): String {
        val ubuntuName = UBUNTU_FILENAME.replace(Regex("-pd.*"), "")
        val tmpDir = File(filesDir, "tmp").absolutePath
        val binDir = binDir.absolutePath
        val homeDir = filesDir.absolutePath
        val usrDir = usrDir.absolutePath
        val prootDistroPath = "$usrDir/var/lib/proot-distro"
        val ubuntuPath = "$prootDistroPath/installed-rootfs/ubuntu"
        val operitPackage = context.packageName
        val operitDataDir = context.applicationInfo.dataDir
        val currentEmulatedStoragePath = PRootMountMapping.currentEmulatedStoragePath()
        val currentUserDataRootPath = PRootMountMapping.currentUserDataRootPath()
        val operitUserDataMountPath = "$currentUserDataRootPath/$operitPackage"
        val operitLegacyDataMountPath = "/data/data/$operitPackage"

        // 获取当前选择的源
        val aptSource = sourceManager.getSelectedSource(PackageManagerType.APT)
        val pipSource = sourceManager.getSelectedSource(PackageManagerType.PIP)
        val npmSource = sourceManager.getSelectedSource(PackageManagerType.NPM)
        val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)

        val common = """
        export TMPDIR=$tmpDir
        export BIN=$binDir
        export HOME=$homeDir
        export UBUNTU_PATH=$ubuntuPath
        export UBUNTU=$UBUNTU_FILENAME
        export UBUNTU_NAME=$ubuntuName
        export USE_CHROOT=${if (prefs.getBoolean("chroot_enabled", false)) "1" else "0"}
        export OPERIT_UID=$(id -u)
        export OPERIT_GID=$(id -g)
        export OPERIT_GROUPS=$(id -G | tr ' ' ',')
        export L_NOT_INSTALLED="not installed"
        export L_INSTALLING="installing"
        export L_INSTALLED="installed"
        clear_lines(){
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
        }
        progress_echo(){
          echo -e "\\033[31m- ${'$'}@\\033[0m"
          echo "${'$'}@" > "${'$'}TMPDIR/progress_des"
        }
        bump_progress(){
          current=0
          if [ -f "${'$'}TMPDIR/progress" ]; then
            current=${'$'}(cat "${'$'}TMPDIR/progress" 2>/dev/null || echo 0)
          fi
          next=${'$'}((current + 1))
          printf "${'$'}next" > "${'$'}TMPDIR/progress"
        }
        write_default_dns(){
          target_file="${'$'}1"
          if [ -z "${'$'}target_file" ]; then
            return 1
          fi
          cat > "${'$'}target_file" <<'EOF'
nameserver 8.8.8.8
nameserver 1.1.1.1
nameserver 223.5.5.5
nameserver 223.6.6.6
nameserver 119.29.29.29
nameserver 180.76.76.76
EOF
        }
        can_access_bind_source(){
          bind_source="${'$'}1"
          if [ -z "${'$'}bind_source" ]; then
            return 1
          fi
          if [ ! -e "${'$'}bind_source" ] && [ ! -L "${'$'}bind_source" ]; then
            return 1
          fi
          "${'$'}BIN/busybox" ls -Ld "${'$'}bind_source" >/dev/null 2>&1
        }
        append_proot_bind_arg(){
          bind_source="${'$'}1"
          bind_target="${'$'}2"
          if ! can_access_bind_source "${'$'}bind_source"; then
            return 0
          fi
          if [ -z "${'$'}bind_target" ] || [ "${'$'}bind_source" = "${'$'}bind_target" ]; then
            PROOT_BIND_ARGS="${'$'}PROOT_BIND_ARGS -b ${'$'}bind_source"
          else
            PROOT_BIND_ARGS="${'$'}PROOT_BIND_ARGS -b ${'$'}bind_source:${'$'}bind_target"
          fi
        }
        run_proot_binary(){
          LD_LIBRARY_PATH= "${'$'}BIN/proot" "${'$'}@"
        }
        exec_proot_binary(){
          LD_LIBRARY_PATH= exec "${'$'}BIN/proot" "${'$'}@"
        }
        LAST_PROOT_PROBE_STATUS=0
        LAST_PROOT_PROBE_OUTPUT=""
        LAST_PROOT_PROBE_ARGS=""
        run_proot_probe(){
          LAST_PROOT_PROBE_ARGS="${'$'}*"
          if [ "${'$'}PROOT_LINK2SYMLINK" = "1" ]; then
            LAST_PROOT_PROBE_OUTPUT="${'$'}(
              run_proot_binary \
                -v 1 \
                -0 \
                -r "${'$'}UBUNTU_PATH" \
                --link2symlink \
                "${'$'}@" \
                -w /root \
                /usr/bin/env -i \
                  HOME=/root \
                  TERM=xterm-256color \
                  LANG=en_US.UTF-8 \
                  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                  /bin/bash --noprofile --norc -c 'exit 0' 2>&1
            )"
            LAST_PROOT_PROBE_STATUS="${'$'}?"
          else
            LAST_PROOT_PROBE_OUTPUT="${'$'}(
              run_proot_binary \
                -v 1 \
                -0 \
                -r "${'$'}UBUNTU_PATH" \
                "${'$'}@" \
                -w /root \
                /usr/bin/env -i \
                  HOME=/root \
                  TERM=xterm-256color \
                  LANG=en_US.UTF-8 \
                  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                  /bin/bash --noprofile --norc -c 'exit 0' 2>&1
            )"
            LAST_PROOT_PROBE_STATUS="${'$'}?"
          fi
          return "${'$'}LAST_PROOT_PROBE_STATUS"
        }
        print_last_proot_probe_failure(){
          echo "PRoot startup probe failed."
          echo "exit_code=${'$'}LAST_PROOT_PROBE_STATUS"
          echo "ubuntu_path=${'$'}UBUNTU_PATH"
          echo "proot_loader=${'$'}{PROOT_LOADER:-<unset>}"
          echo "proot_no_seccomp=${'$'}{PROOT_NO_SECCOMP:-<unset>}"
          echo "ld_library_path=${'$'}{LD_LIBRARY_PATH:-<unset>}"
          echo "proot_exec_ld_library_path=<empty>"
          echo "proot_link2symlink=${'$'}PROOT_LINK2SYMLINK"
          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            echo "bind_args=${'$'}PROOT_BIND_ARGS"
          else
            echo "bind_args=<none>"
          fi
          if [ -n "${'$'}LAST_PROOT_PROBE_ARGS" ]; then
            echo "extra_args=${'$'}LAST_PROOT_PROBE_ARGS"
          else
            echo "extra_args=<none>"
          fi
          echo "--- proot stdout/stderr begin ---"
          if [ -n "${'$'}LAST_PROOT_PROBE_OUTPUT" ]; then
            printf '%s\n' "${'$'}LAST_PROOT_PROBE_OUTPUT"
          else
            echo "<empty>"
          fi
          echo "--- proot stdout/stderr end ---"
        }
        probe_proot_link2symlink(){
          run_proot_binary \
            -v 1 \
            -0 \
            -r "${'$'}UBUNTU_PATH" \
            --link2symlink \
            -w /root \
            /usr/bin/env -i \
              HOME=/root \
              TERM=xterm-256color \
              LANG=en_US.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              /bin/bash --noprofile --norc -c 'exit 0' >/dev/null 2>&1
        }
        probe_and_append_bind_arg(){
          bind_source="${'$'}1"
          bind_target="${'$'}2"
          if ! can_access_bind_source "${'$'}bind_source"; then
            return 0
          fi

          candidate_bind_args="-b ${'$'}bind_source"
          if [ -n "${'$'}bind_target" ] && [ "${'$'}bind_source" != "${'$'}bind_target" ]; then
            candidate_bind_args="-b ${'$'}bind_source:${'$'}bind_target"
          fi

          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            set -- ${'$'}PROOT_BIND_ARGS ${'$'}candidate_bind_args
          else
            set -- ${'$'}candidate_bind_args
          fi

          if run_proot_probe "${'$'}@"; then
            append_proot_bind_arg "${'$'}bind_source" "${'$'}bind_target"
          fi
        }
        resolve_proot_runtime(){
          PROOT_LINK2SYMLINK=0
          PROOT_BIND_ARGS=""

          if ! run_proot_probe; then
            print_last_proot_probe_failure
            return 1
          fi

          if probe_proot_link2symlink; then
            PROOT_LINK2SYMLINK=1
          fi
          return 0
        }
        """.trimIndent()

        val installUbuntu = """
        install_ubuntu(){
          OK_FILE="${'$'}UBUNTU_PATH/.operit_installed_ok"
          LOCK_DIR="${'$'}UBUNTU_PATH.install.lock"
          LOCK_PID_FILE="${'$'}LOCK_DIR/pid"
          TMP_DIR="${'$'}UBUNTU_PATH.install.tmp"

          UBUNTU_PARENT="${'$'}{UBUNTU_PATH%/*}"
          mkdir -p "${'$'}UBUNTU_PARENT" 2>/dev/null

          attempt=0
          while true; do
            if mkdir "${'$'}LOCK_DIR" 2>/dev/null; then
              echo "${'$'}${'$'}" > "${'$'}LOCK_PID_FILE" 2>/dev/null || true
              break
            fi

            if [ -f "${'$'}LOCK_PID_FILE" ]; then
              lock_pid=${'$'}(cat "${'$'}LOCK_PID_FILE" 2>/dev/null)
              if [ -z "${'$'}lock_pid" ]; then
                if [ "${'$'}attempt" -gt 2 ]; then
                  rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                  continue
                fi
              elif ! kill -0 "${'$'}lock_pid" 2>/dev/null; then
                rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                continue
              fi
            else
              if [ "${'$'}attempt" -gt 2 ]; then
                rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                continue
              fi
            fi

            attempt=${'$'}((attempt + 1))
            if [ "${'$'}attempt" -gt 120 ]; then
              progress_echo "Ubuntu install lock timeout"
              return 1
            fi
            sleep 1
          done

          cleanup_install(){
            rm -rf "${'$'}TMP_DIR" 2>/dev/null
            rm -rf "${'$'}LOCK_DIR" 2>/dev/null
          }
          trap 'cleanup_install' EXIT INT TERM

          if [ -f "${'$'}OK_FILE" ]; then
            VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
            progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
          else
            if [ -f "${'$'}UBUNTU_PATH/etc/issue.net" ]; then
              echo "ok" > "${'$'}OK_FILE" 2>/dev/null || true
              VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
              progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
            else
              progress_echo "Ubuntu ${'$'}L_NOT_INSTALLED, ${'$'}L_INSTALLING..."
              if [ ! -f "${'$'}HOME/${'$'}UBUNTU" ]; then
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              rm -rf "${'$'}TMP_DIR" 2>/dev/null
              mkdir -p "${'$'}TMP_DIR" 2>/dev/null
              progress_echo "Extracting Ubuntu rootfs..."
              busybox tar xf "${'$'}HOME/${'$'}UBUNTU" -C "${'$'}TMP_DIR"/ >/dev/null 2>&1
              if [ ${'$'}? -ne 0 ]; then
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              echo "Extraction complete"
              if [ -d "${'$'}TMP_DIR/${'$'}UBUNTU_NAME" ]; then
                mv "${'$'}TMP_DIR/${'$'}UBUNTU_NAME"/* "${'$'}TMP_DIR"/ 2>/dev/null
                rm -rf "${'$'}TMP_DIR/${'$'}UBUNTU_NAME" 2>/dev/null
              fi

              mkdir -p "${'$'}TMP_DIR/root" 2>/dev/null
              echo 'export ANDROID_DATA=/home/' >> "${'$'}TMP_DIR/root/.bashrc"
              mkdir -p "${'$'}TMP_DIR/etc" 2>/dev/null
              write_default_dns "${'$'}TMP_DIR/etc/resolv.conf"
              echo "ok" > "${'$'}TMP_DIR/.operit_installed_ok" 2>/dev/null || true

              rm -rf "${'$'}UBUNTU_PATH" 2>/dev/null
              mv "${'$'}TMP_DIR" "${'$'}UBUNTU_PATH" 2>/dev/null
              if [ ${'$'}? -ne 0 ]; then
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              rm -f "${'$'}HOME/${'$'}UBUNTU" 2>/dev/null
            fi
          fi

          mkdir -p ${'$'}UBUNTU_PATH/etc 2>/dev/null
          write_default_dns "${'$'}UBUNTU_PATH/etc/resolv.conf"

          rm -rf "${'$'}LOCK_DIR" 2>/dev/null
          trap - EXIT INT TERM
        }
        """.trimIndent()

        val configureSources = """
        configure_sources(){
          # 配置APT源
          cat <<'EOF' > ${'$'}UBUNTU_PATH/etc/apt/sources.list
        # From Operit Settings - ${aptSource.name}
        deb ${aptSource.url} noble main restricted universe multiverse
        deb ${aptSource.url} noble-updates main restricted universe multiverse
        deb ${aptSource.url} noble-backports main restricted universe multiverse
        EOF
          
          # 配置Pip/Uv源
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/pip 2>/dev/null
          echo '[global]' > ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          echo 'index-url = ${pipSource.url}' >> ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/uv 2>/dev/null
          echo 'index-url = "${pipSource.url}"' > ${'$'}UBUNTU_PATH/root/.config/uv/uv.toml
          
          # 配置NPM源
          mkdir -p ${'$'}UBUNTU_PATH/root 2>/dev/null
          echo 'registry=${npmSource.url}' > ${'$'}UBUNTU_PATH/root/.npmrc
        }
        """.trimIndent()

        val fixPermissions = """
        fix_permissions(){
          echo "Fixing permissions..."
          # Fix "cannot find name for group ID" warnings
          # Append Android groups to Ubuntu /etc/group
          current_groups=$(id -G)
          for gid in ${'$'}current_groups; do
            if ! grep -q ":${'$'}gid:" ${'$'}UBUNTU_PATH/etc/group; then
              echo "android_group_${'$'}gid:x:${'$'}gid:" >> ${'$'}UBUNTU_PATH/etc/group
            fi
          done
          echo "Permissions fixed."
        }
        """.trimIndent()

        // 读取共享tmp设置
        val sharedTmpEnabled = prefs.getBoolean("shared_tmp_enabled", true)
        val prootBindSetup = PRootMountMapping.buildRuntimeBindMounts(
            homeDir = homeDir,
            appDataDir = operitDataDir,
            packageName = operitPackage,
            chrootEnabled = false
        ).toMutableList().apply {
            if (sharedTmpEnabled) {
                add(4, PRootBindMount(tmpDir, "/dev/shm"))
            }
        }.joinToString(separator = "\n") { mount ->
            "          probe_and_append_bind_arg \"${mount.sourcePath}\" \"${mount.targetPath}\""
        }
        
        val configureVisibleShell = """
        configure_visible_shell(){
          mkdir -p "${'$'}UBUNTU_PATH/etc/operit" || return
          cat <<'OPERIT_BASHRC' > "${'$'}UBUNTU_PATH/etc/operit/terminal.bashrc"
        # 载入原登录配置后再设置应用提示符，不改写用户的配置文件。
        if [ -r /etc/profile ]; then . /etc/profile; fi
        if [ -r "${'$'}HOME/.bash_profile" ]; then
          . "${'$'}HOME/.bash_profile"
        elif [ -r "${'$'}HOME/.bash_login" ]; then
          . "${'$'}HOME/.bash_login"
        elif [ -r "${'$'}HOME/.profile" ]; then
          . "${'$'}HOME/.profile"
        fi
        # 保留已有提示符钩子，并在其后恢复零宽度提示符及原退出状态。
        __operit_terminal_prompt(){
          local __operit_status=${'$'}?
          __operit_prompt_directory=${'$'}{PWD//%/%25}
          __operit_prompt_directory=${'$'}{__operit_prompt_directory//${'$'}'\a'/%07}
          __operit_prompt_directory=${'$'}{__operit_prompt_directory//${'$'}'\e'/%1B}
          __operit_prompt_directory=${'$'}{__operit_prompt_directory//${'$'}'\r'/%0D}
          __operit_prompt_directory=${'$'}{__operit_prompt_directory//${'$'}'\n'/%0A}
          PS1='\[\e]777;operit-prompt;${'$'}__operit_prompt_directory\a\]'
          return "${'$'}__operit_status"
        }
        PROMPT_COMMAND+=(__operit_terminal_prompt)
        __operit_terminal_prompt
        OPERIT_BASHRC
        }
        """.trimIndent()

        // 初始化脚本通过独立环境变量传入，避免嵌套 shell 展开路径、引号和美元符号。
        val localSessionInit = localSessionInitializer(context)
        val quotedLocalSessionInit = "'" + localSessionInit.replace("'", "'\"'\"'") + "'"
        val loginUbuntu = """
        login_ubuntu(){
          COMMAND_TO_EXEC="$1"
          OPERIT_LOCAL_SESSION_INIT=$quotedLocalSessionInit
          AUTOMATION_ENV=""
          if [ "${'$'}OPERIT_AUTOMATION" = "1" ]; then
            AUTOMATION_ENV="PAGER=cat GIT_PAGER=cat GIT_TERMINAL_PROMPT=0"
          fi
          if [ -z "${'$'}COMMAND_TO_EXEC" ]; then
            COMMAND_TO_EXEC="/bin/bash --rcfile /etc/operit/terminal.bashrc -i"
          fi

          # Setup fake sysdata
          if [ "${'$'}USE_CHROOT" != "1" ]; then
            export INSTALLED_ROOTFS_DIR=$(dirname "${'$'}UBUNTU_PATH")
            export distro_name=$(basename "${'$'}UBUNTU_PATH")
            
            if [ -f "${'$'}HOME/setup_fake_sysdata.sh" ]; then
                source "${'$'}HOME/setup_fake_sysdata.sh"
                setup_fake_sysdata
            fi
          fi

          # 使用 proot 直接进入解压的 Ubuntu 根文件系统。
          # - 清理并设置 PATH，避免继承宿主 PATH 造成命令找不到或混用 busybox。
          # - 绑定常见伪文件系统、外部存储与 Operit 应用沙箱，保障交互和软件包管理工作正常。
          # 在 proot 环境中创建必要的目录
          mkdir -p "${'$'}UBUNTU_PATH/storage/emulated" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$operitUserDataMountPath" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$operitLegacyDataMountPath" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH/data/local/tmp" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$homeDir" 2>/dev/null

          if [ "${'$'}USE_CHROOT" = "1" ]; then
            CMD_FILE="${'$'}TMPDIR/command_to_exec"
            printf "%s" "${'$'}COMMAND_TO_EXEC" > "${'$'}CMD_FILE" 2>/dev/null || true
            INIT_FILE="${'$'}TMPDIR/local_session_init"
            printf "%s" "${'$'}OPERIT_LOCAL_SESSION_INIT" > "${'$'}INIT_FILE" || return 1
            CHROOT_WRAPPER="${'$'}TMPDIR/operit_chroot_wrapper.sh"
            cat > "${'$'}CHROOT_WRAPPER" <<'EOF'
        BIN="$1"
        UBUNTU_PATH="$2"
        CMD_FILE="$3"
        HOME_DIR="$4"
        OPERIT_UID="$5"
        OPERIT_GID="$6"
        OPERIT_GROUPS="$7"
        INIT_FILE="$8"
        AUTOMATION_ENV=""
        if [ "$9" = "1" ]; then AUTOMATION_ENV="PAGER=cat GIT_PAGER=cat GIT_TERMINAL_PROMPT=0"; fi
        mount_bind(){
          source_path="${'$'}1"
          target_path="${'$'}2"
          if ! "${'$'}BIN/busybox" mount --bind "${'$'}source_path" "${'$'}target_path"; then
            echo "chroot bind mount failed: ${'$'}source_path -> ${'$'}target_path" >&2
            return 1
          fi
          if ! "${'$'}BIN/busybox" mountpoint -q "${'$'}target_path"; then
            echo "chroot bind mount verification failed: ${'$'}target_path" >&2
            return 1
          fi
          echo "chroot bind mount active: ${'$'}target_path"
        }
        cleanup_mounts(){
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/dev/pts" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/dev" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/sys" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/proc" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH$currentUserDataRootPath" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/data/data" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/data/local/tmp" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/sdcard" 2>/dev/null || true
        }
        cleanup_mounts

        "${'$'}BIN/busybox" mkdir -p "${'$'}UBUNTU_PATH/proc" "${'$'}UBUNTU_PATH/sys" "${'$'}UBUNTU_PATH/dev" "${'$'}UBUNTU_PATH/dev/pts" "${'$'}UBUNTU_PATH/sdcard" "${'$'}UBUNTU_PATH$currentUserDataRootPath" "${'$'}UBUNTU_PATH/data/data" "${'$'}UBUNTU_PATH/data/local/tmp" "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null
        "${'$'}BIN/busybox" mount -t proc proc "${'$'}UBUNTU_PATH/proc" 2>/dev/null || true
        mount_bind /dev "${'$'}UBUNTU_PATH/dev"
        mount_bind /sys "${'$'}UBUNTU_PATH/sys"
        mount_bind /dev/pts "${'$'}UBUNTU_PATH/dev/pts"
        mount_bind "$currentEmulatedStoragePath" "${'$'}UBUNTU_PATH/sdcard"
        mount_bind "$currentUserDataRootPath" "${'$'}UBUNTU_PATH$currentUserDataRootPath"
        mount_bind /data/data "${'$'}UBUNTU_PATH/data/data"
        mount_bind /data/local/tmp "${'$'}UBUNTU_PATH/data/local/tmp"
        mount_bind "${'$'}HOME_DIR" "${'$'}UBUNTU_PATH${'$'}HOME_DIR"
        COMMAND_TO_EXEC="$(cat "${'$'}CMD_FILE" 2>/dev/null)"
        OPERIT_LOCAL_SESSION_INIT="$(cat "${'$'}INIT_FILE")" || exit 1
        "${'$'}BIN/busybox" chroot "${'$'}UBUNTU_PATH" /usr/bin/env -i ${'$'}AUTOMATION_ENV HOME=/root TERM=xterm-256color LANG=en_US.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "COMMAND_TO_EXEC=${'$'}COMMAND_TO_EXEC" "OPERIT_LOCAL_SESSION_INIT=${'$'}OPERIT_LOCAL_SESSION_INIT" "OPERIT_UID=${'$'}OPERIT_UID" "OPERIT_GID=${'$'}OPERIT_GID" "OPERIT_GROUPS=${'$'}OPERIT_GROUPS" /bin/bash -lc 'eval "${'$'}OPERIT_LOCAL_SESSION_INIT" || exit ${'$'}?; echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; umask 0002; if [ -n "${'$'}OPERIT_GID" ]; then chown 0:"${'$'}OPERIT_GID" /root 2>/dev/null || true; chmod 2775 /root 2>/dev/null || true; fi; eval "${'$'}COMMAND_TO_EXEC"'
        ret=${'$'}?
        if [ -n "${'$'}OPERIT_UID" ] && [ -n "${'$'}OPERIT_GID" ]; then
          for apt_path in /var/lib/apt/lists /var/cache/apt; do
            if [ -e "${'$'}UBUNTU_PATH${'$'}apt_path" ] && ! "${'$'}BIN/busybox" chown -R "${'$'}OPERIT_UID:${'$'}OPERIT_GID" "${'$'}UBUNTU_PATH${'$'}apt_path" 2>/dev/null; then
              echo "Warning: failed to restore proot ownership for ${'$'}apt_path" >&2
            fi
          done
        fi
        cleanup_mounts
        exit ${'$'}ret
        EOF
            chmod 700 "${'$'}CHROOT_WRAPPER" 2>/dev/null || true
            exec su -c "sh \"${'$'}CHROOT_WRAPPER\" \"${'$'}BIN\" \"${'$'}UBUNTU_PATH\" \"${'$'}CMD_FILE\" \"${homeDir}\" \"${'$'}OPERIT_UID\" \"${'$'}OPERIT_GID\" \"${'$'}OPERIT_GROUPS\" \"${'$'}INIT_FILE\" \"${'$'}OPERIT_AUTOMATION\""
          fi
          if ! resolve_proot_runtime; then
            return 1
          fi
$prootBindSetup
          if [ "${'$'}USE_CHROOT" != "1" ]; then
            if [ ! -e /proc/stat ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.stat" "/proc/stat"; fi
            if [ ! -e /proc/loadavg ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.loadavg" "/proc/loadavg"; fi
            if [ ! -e /proc/uptime ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.uptime" "/proc/uptime"; fi
            if [ ! -e /proc/version ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.version" "/proc/version"; fi
            if [ ! -e /proc/vmstat ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.vmstat" "/proc/vmstat"; fi
            if [ ! -e /proc/sys/kernel/cap_last_cap ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.sysctl_entry_cap_last_cap" "/proc/sys/kernel/cap_last_cap"; fi
            if [ ! -e /proc/sys/fs/inotify/max_user_watches ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.sysctl_inotify_max_user_watches" "/proc/sys/fs/inotify/max_user_watches"; fi
          fi
          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            set -- ${'$'}PROOT_BIND_ARGS
          else
            set --
          fi
          if [ "${'$'}PROOT_LINK2SYMLINK" = "1" ]; then
            exec_proot_binary \
              -0 \
              -r "${'$'}UBUNTU_PATH" \
              --link2symlink \
              "${'$'}@" \
              -w /root \
              /usr/bin/env -i \
                ${'$'}AUTOMATION_ENV \
                HOME=/root \
                TERM=xterm-256color \
                LANG=en_US.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                COMMAND_TO_EXEC="${'$'}COMMAND_TO_EXEC" \
                OPERIT_LOCAL_SESSION_INIT="${'$'}OPERIT_LOCAL_SESSION_INIT" \
                /bin/bash -lc 'eval "${'$'}OPERIT_LOCAL_SESSION_INIT" || exit ${'$'}?; echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; eval "${'$'}COMMAND_TO_EXEC"'
          else
            exec_proot_binary \
              -0 \
              -r "${'$'}UBUNTU_PATH" \
              "${'$'}@" \
              -w /root \
              /usr/bin/env -i \
                ${'$'}AUTOMATION_ENV \
                HOME=/root \
                TERM=xterm-256color \
                LANG=en_US.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                COMMAND_TO_EXEC="${'$'}COMMAND_TO_EXEC" \
                OPERIT_LOCAL_SESSION_INIT="${'$'}OPERIT_LOCAL_SESSION_INIT" \
                /bin/bash -lc 'eval "${'$'}OPERIT_LOCAL_SESSION_INIT" || exit ${'$'}?; echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; eval "${'$'}COMMAND_TO_EXEC"'
          fi
        }
        """.trimIndent()

        val sshShell = """
        ssh_shell(){
          set -x
          install_ubuntu
          configure_sources
          fix_permissions
          configure_visible_shell || return
          sleep 1
          bump_progress
          
          # 先进入Ubuntu环境，然后连接SSH
          # 当SSH退出时，用户会回到本地Ubuntu shell
          login_ubuntu 'echo "Connecting to SSH..."; '"${'$'}SSH_COMMAND"'; echo "SSH connection closed. You are now in local Ubuntu terminal."; /bin/bash --rcfile /etc/operit/terminal.bashrc -i'
        }
        """.trimIndent()

        return """
        $common
        $installUbuntu
        $configureSources
        $fixPermissions
        $configureVisibleShell
        $loginUbuntu
        $sshShell
        clear_lines
        start_shell(){
          install_ubuntu
          configure_sources
          fix_permissions
          configure_visible_shell || return
          sleep 1
          bump_progress
          login_ubuntu
        }
        """.trimIndent()
    }

    fun closeTerminalSession(sessionId: String) {
        closingSessions.add(sessionId)
        sessionManager.getSession(sessionId)?.exitJob?.cancel()
        sessionInputs.remove(sessionId)?.cancel()
        inputJobs.remove(sessionId)?.cancel()
        sessionProcesses[sessionId]?.let { destroyAndReapSessionProcess(sessionId, it) }
        coroutineScope.launch {
            runCatching { terminalProvider?.closeSession(sessionId) }
                .onFailure { Log.w(TAG, "Error closing session via provider: $sessionId", it) }
        }
        activeSessions.remove(sessionId)
    }

    private fun writeStartScriptAtomically() {
        val contents = generateStartScript().replace("\r\n", "\n").replace("\r", "\n")
        val staged = File.createTempFile("common.sh.", ".pending", filesDir)
        try {
            staged.outputStream().use { output ->
                output.write(contents.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(staged.absolutePath, File(filesDir, "common.sh").absolutePath)
        } finally {
            staged.delete()
        }
    }

    private fun destroyAndReapSessionProcess(sessionId: String, process: Process) {
        lateinit var job: kotlinx.coroutines.Job
        job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(NonCancellable) {
                    val pty = sessionPtys.remove(sessionId)
                    if (pty != null) {
                        if (pty.pid > 0 && !pty.terminateSession(2_000L)) {
                            Log.w(TAG, "Terminal descendants were not confirmed stopped: $sessionId")
                        }
                        pty.destroy()
                    } else {
                        process.destroy()
                    }
                    val exited = withTimeoutOrNull(2_000L) {
                        while (process.isAlive) delay(25L)
                        true
                    } == true
                    if (exited) sessionProcesses.remove(sessionId, process)
                    else Log.w(TAG, "Terminal process did not stop: $sessionId")
                }
            } catch (error: Exception) {
                Log.w(TAG, "Terminal resource cleanup failed: $sessionId", error)
            } finally {
                processReapJobs.remove(sessionId, job)
            }
        }
        if (processReapJobs.putIfAbsent(sessionId, job) == null) job.start() else job.cancel()
    }

    internal fun closeSessionWriter(session: TerminalSessionData) {
        coroutineScope.launch(Dispatchers.IO) {
            processReapJobs[session.id]?.join()
            runCatching { session.sessionWriter?.close() }
                .onFailure { Log.w(TAG, "Failed to close terminal writer: ${session.id}", it) }
        }
    }

    private fun handleRegularCommand(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData, commandId: String) {
        session.currentCommandOutput.clear()
        session.currentOutputLineCount = 0

        val newCommandItem = CommandHistoryItem(
            id = commandId,
            prompt = session.currentDirectory,
            command = command,
            output = "",
            isExecuting = true
        )

        // Set the current executing command reference for efficient access
        session.currentExecutingCommand = newCommandItem

        // 发出命令开始执行事件
        commandEventChannel.trySend(CommandExecutionEvent(
            commandId = newCommandItem.id, sessionId = session.id, outputChunk = "", isCompleted = false,
        ))
    }

    fun prepareForMaintenance() {
        // 释放 provider 连接
        kotlinx.coroutines.runBlocking {
            terminalProvider?.disconnect()
            Log.d(TAG, "Disconnected terminal provider")
            
            // 停止SSHD服务器
            sshdServerManager.stopServer()
            Log.d(TAG, "Stopped SSHD server")
        }
        terminalProvider = null

        // 删除 Ubuntu 前必须等待本地 PRoot/终端进程退出，避免清理时仍有文件被重新创建。
        val processes = activeSessions.values.map { it.process }
        activeSessions.keys.toList().forEach { sessionId ->
            closeTerminalSession(sessionId)
        }
        processes.forEach { process ->
            if (process.isAlive) {
                runCatching {
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        Log.w(TAG, "Terminal process did not exit before maintenance cleanup")
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Failed waiting for terminal process during maintenance", error)
                }
            }
        }
        sessionManager.cleanup()
        // 环境删除前等待异步退役结束，不能只以状态表已清空判断资源已释放。
        val cleanupComplete = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(5_000L) {
                processReapJobs.values.toList().forEach { it.join() }
                true
            } == true
        }
        check(cleanupComplete && sessionProcesses.values.none { it.isAlive }) {
            "Terminal cleanup is incomplete; environment maintenance cannot continue"
        }
        Log.d(TAG, "Prepared terminal manager for maintenance.")
    }

    fun cleanup() {
        prepareForMaintenance()
        sessionInputs.values.forEach { it.cancel() }
        inputJobs.values.forEach { it.cancel() }
        commandEventChannel.close()
        coroutineScope.cancel()
        Log.d(TAG, "All active sessions cleaned up.")
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = 120000L
    ): HiddenExecResult {
        val initialized = initializeEnvironment()
        if (!initialized) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.SHELL_START_FAILED,
                error = "Terminal environment initialization failed"
            )
        }

        return getTerminalProvider().executeHiddenCommand(
            command = command,
            executorKey = executorKey,
            timeoutMs = timeoutMs
        )
    }

    /**
     * 获取文件系统提供者
     * 
     * 根据配置返回对应的提供者（本地或SSH）
     * 如果配置了SSH且已连接，返回SSH的文件系统提供者（共享SFTP连接）
     * 否则返回本地文件系统提供者
     */
    fun getFileSystemProvider(): FileSystemProvider = kotlinx.coroutines.runBlocking {
        getTerminalProvider().getFileSystemProvider()
    }
    
    /**
     * 获取SSHD服务器管理器
     * 
     * 用于管理本地SSHD服务器（反向SSH隧道场景）
     */
    fun getSSHDServerManager(): SSHDServerManager = sshdServerManager
}
