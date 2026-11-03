package com.ai.assistance.operit.terminal.command

import kotlinx.coroutines.sync.Mutex

/** 数据快照复制时共享同一生命周期，关闭和完成事件只能执行一次。 */
class CommandLifecycle {
    @Volatile var closed = false
    var cancellingCommandId: String? = null
    internal var cancellationRecovery: CommandCancellationRecovery? = null
    val cancellationMutex = Mutex()
    var latestScreen: String? = null
}

/** 区分取消请求完成与底层进程是否已确认停止。 */
enum class CommandCancellationOutcome {
    REMOVED_FROM_QUEUE,
    INTERRUPTED,
    SESSION_CLOSED,
    CLEANUP_INCOMPLETE,
    ALREADY_FINISHED,
}
