package com.ai.assistance.operit.terminal.view.domain.shell

import com.ai.assistance.operit.terminal.command.CommandCancellationRecovery

/** 从 PTY 字节块中分离本地提示符和取消确认，保留正文与事件的原始顺序。 */
internal class ShellPromptStream {
    private val pending = StringBuilder()
    var hasPromptProtocol: Boolean = false
        private set

    sealed interface Part {
        data class Output(val text: String) : Part
        data class Prompt(val directory: String) : Part
        data class CancellationAcknowledgement(val token: String) : Part
    }

    fun feed(chunk: String): List<Part> {
        pending.append(chunk)
        val parts = mutableListOf<Part>()
        while (pending.isNotEmpty()) {
            val marker = PREFIXES.mapNotNull { prefix ->
                pending.indexOf(prefix).takeIf { it >= 0 }?.let { it to prefix }
            }.minByOrNull { it.first }
            if (marker == null) {
                // 两种控制标记都可能跨块，只保留其中最长的待续前缀。
                val retained = PREFIXES.maxOf { prefix ->
                    var length = minOf(pending.length, prefix.length - 1)
                    while (length > 0 && !pending.endsWith(prefix.take(length))) length--
                    length
                }
                val outputLength = pending.length - retained
                if (outputLength > 0) {
                    parts.add(Part.Output(pending.substring(0, outputLength)))
                    pending.delete(0, outputLength)
                }
                break
            }
            val (promptIndex, prefix) = marker
            if (promptIndex > 0) {
                parts.add(Part.Output(pending.substring(0, promptIndex)))
                pending.delete(0, promptIndex)
            }
            val end = pending.indexOf("\u0007", prefix.length)
            if (end < 0) {
                if (pending.length > 65_536) {
                    parts.add(Part.Output(pending.toString()))
                    pending.clear()
                }
                break
            }
            val payload = pending.substring(prefix.length, end)
            if (prefix == PROMPT_PREFIX) {
                hasPromptProtocol = true
                val directory = payload.replace("%07", "\u0007").replace("%1B", "\u001b")
                    .replace("%0D", "\r").replace("%0A", "\n").replace("%25", "%")
                parts.add(Part.Prompt(directory))
            } else {
                parts.add(Part.CancellationAcknowledgement(payload))
            }
            pending.delete(0, end + 1)
        }
        return parts
    }

    companion object {
        private const val PROMPT_PREFIX = "\u001b]777;operit-prompt;"
        private val PREFIXES = listOf(PROMPT_PREFIX, "\u001b]777;${CommandCancellationRecovery.PROTOCOL_NAME}")
    }
}
