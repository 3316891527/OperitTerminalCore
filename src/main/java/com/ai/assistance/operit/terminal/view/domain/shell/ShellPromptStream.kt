package com.ai.assistance.operit.terminal.view.domain.shell

/** 从 PTY 字节块中分离不占屏幕宽度的本地目录提示符，保留正文与事件的原始顺序。 */
internal class ShellPromptStream {
    private val pending = StringBuilder()
    var hasPromptProtocol: Boolean = false
        private set

    sealed interface Part {
        data class Output(val text: String) : Part
        data class Prompt(val directory: String) : Part
    }

    fun feed(chunk: String): List<Part> {
        pending.append(chunk)
        val parts = mutableListOf<Part>()
        while (pending.isNotEmpty()) {
            val promptIndex = pending.indexOf(PREFIX)
            if (promptIndex < 0) {
                // 只保留可能跨块的控制标记前缀，普通文本立即交给原输出管线。
                var retained = minOf(pending.length, PREFIX.length - 1)
                while (retained > 0 && !pending.endsWith(PREFIX.take(retained))) retained--
                val outputLength = pending.length - retained
                if (outputLength > 0) {
                    parts.add(Part.Output(pending.substring(0, outputLength)))
                    pending.delete(0, outputLength)
                }
                break
            }
            if (promptIndex > 0) {
                parts.add(Part.Output(pending.substring(0, promptIndex)))
                pending.delete(0, promptIndex)
            }
            val end = pending.indexOf("\u0007", PREFIX.length)
            if (end < 0) {
                if (pending.length > 65_536) {
                    parts.add(Part.Output(pending.toString()))
                    pending.clear()
                }
                break
            }
            hasPromptProtocol = true
            val directory = pending.substring(PREFIX.length, end)
                .replace("%07", "\u0007").replace("%1B", "\u001b")
                .replace("%0D", "\r").replace("%0A", "\n").replace("%25", "%")
            parts.add(Part.Prompt(directory))
            pending.delete(0, end + 1)
        }
        return parts
    }

    companion object {
        private const val PREFIX = "\u001b]777;operit-prompt;"
    }
}
