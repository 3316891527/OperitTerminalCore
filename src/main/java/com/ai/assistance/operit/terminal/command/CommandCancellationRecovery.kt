package com.ai.assistance.operit.terminal.command

import java.util.UUID

/** 仅在中断发出后验证原 shell 恢复；所有状态都由所属生命周期的监视器保护。 */
internal class CommandCancellationRecovery(
    val commandId: String,
    val token: String = UUID.randomUUID().toString(),
) {
    private var interruptDispatched = false
    private var probeQueued = false
    private var probeDispatched = false

    // 控制序列由 printf 生成，PTY 对输入行的回显不会构成有效回复。
    val probeInput: String = "builtin printf '\\n\\033]777;$PROTOCOL_NAME%s\\007\\n' '$token'\r"

    fun onInterruptDispatched() {
        interruptDispatched = true
    }

    fun queueProbeAfterPrompt(): Boolean {
        if (!interruptDispatched || probeQueued) return false
        probeQueued = true
        return true
    }

    fun onProbeDispatched() {
        probeQueued = false
        probeDispatched = true
    }

    fun acceptsAcknowledgement(receivedToken: String): Boolean =
        interruptDispatched && probeDispatched && receivedToken == token

    companion object {
        const val PROTOCOL_NAME = "operit-cancel;"
    }
}
