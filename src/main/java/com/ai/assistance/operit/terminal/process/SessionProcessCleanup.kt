package com.ai.assistance.operit.terminal.process

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal data class SessionProcessIdentity(
    val pid: Int,
    val sessionId: Int,
    val startTime: Long,
    val state: String,
)

/** comm 可以包含空格和括号，固定字段从最后一个右括号之后开始计算。 */
internal fun parseSessionProcessStat(text: String): SessionProcessIdentity? = runCatching {
    val end = text.lastIndexOf(')')
    require(end > 0)
    val fields = text.substring(end + 1).trim().split(Regex("\\s+"))
    SessionProcessIdentity(
        pid = text.substringBefore(' ').toInt(),
        sessionId = fields[3].toInt(),
        startTime = fields[19].toLong(),
        state = fields[0],
    )
}.getOrNull()

internal fun readSessionProcess(pid: Int): SessionProcessIdentity? =
    runCatching { parseSessionProcessStat(File("/proc/$pid/stat").readText()) }.getOrNull()

/** 仅用于退役整个本地 PTY；每次发信号前核对启动时间，防止 PID 复用后误杀。 */
internal suspend fun terminatePtySession(owner: SessionProcessIdentity, timeoutMs: Long): Boolean {
    if (owner.pid <= 1 || owner.sessionId != owner.pid) return false
    val knownMembers = mutableMapOf(owner.pid to owner)
    fun missing(pid: Int): Boolean = try {
        Os.kill(pid, 0)
        false
    } catch (error: ErrnoException) {
        error.errno == OsConstants.ESRCH
    }
    fun exited(member: SessionProcessIdentity): Boolean {
        val current = readSessionProcess(member.pid) ?: return missing(member.pid)
        return current.startTime != member.startTime || current.state in setOf("Z", "X")
    }
    fun members(): List<SessionProcessIdentity>? {
        val currentOwner = readSessionProcess(owner.pid)
        if (currentOwner == null && !missing(owner.pid)) return null
        if (currentOwner != null &&
            (currentOwner.startTime != owner.startTime || currentOwner.sessionId != owner.sessionId)) return null
        return File("/proc").listFiles()?.mapNotNull { entry ->
            entry.name.toIntOrNull()?.let(::readSessionProcess)
        }?.filter { it.sessionId == owner.sessionId && it.state !in setOf("Z", "X") }
            ?.also { list -> list.forEach { knownMembers[it.pid] = it } }
    }
    fun allExited(): Boolean = members()?.isEmpty() == true && knownMembers.values.all(::exited)
    fun signal(member: SessionProcessIdentity, value: Int) {
        val current = readSessionProcess(member.pid) ?: return
        if (current.startTime == member.startTime && current.sessionId == owner.sessionId) {
            runCatching { Os.kill(member.pid, value) }
        }
    }
    val initial = members() ?: return false
    // 先通知 PRoot 跟踪器回收被跟踪进程，再处理仍未退出的同会话成员。
    initial.filter { member ->
        val executable = runCatching {
            File("/proc/${member.pid}/cmdline").readText().substringBefore('\u0000').substringAfterLast('/')
        }.getOrNull()
        executable in setOf("proot", "proot.bin", "libproot.so")
    }.forEach { signal(it, OsConstants.SIGQUIT) }
    val graceful = withTimeoutOrNull((timeoutMs / 2).coerceAtLeast(1L)) {
        while (!allExited()) delay(25L)
        true
    }
    if (graceful == true) return true
    return withTimeoutOrNull((timeoutMs / 2).coerceAtLeast(1L)) {
        while (true) {
            val remaining = members() ?: return@withTimeoutOrNull false
            if (remaining.isEmpty() && knownMembers.values.all(::exited)) return@withTimeoutOrNull true
            // 保持跟踪器最后退出，优先终止仍持有终端文件描述符的后代进程。
            remaining.sortedBy { it.pid == owner.pid }.forEach { signal(it, OsConstants.SIGKILL) }
            delay(25L)
        }
        @Suppress("UNREACHABLE_CODE")
        false
    } == true
}
