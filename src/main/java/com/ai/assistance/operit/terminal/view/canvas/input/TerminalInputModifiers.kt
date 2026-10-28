package com.ai.assistance.operit.terminal.view.canvas.input

/** 宿主虚拟修饰键与实体键共用终端编码，不修改控制序列中的字母。 */
fun applyTerminalModifiers(input: String, ctrl: Boolean, alt: Boolean): String {
    if (!ctrl && !alt) return input
    if (input.length == 3 && input.startsWith("\u001bO") && input.last() in "ABCDHFPQRS") {
        val modifier = 1 + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        return "\u001b[1;${modifier}${input.last()}"
    }
    // 方向键和翻页键使用 xterm 修饰参数，不能把转义序列中的字母转换成 Ctrl 字符。
    if (input.length > 2 && input.startsWith("\u001b[")) {
        val modifier = 1 + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        val suffix = input.last()
        val parameters = input.substring(2, input.lastIndex)
        return when {
            suffix in "ABCDHF" && parameters.isEmpty() -> "\u001b[1;${modifier}$suffix"
            suffix == '~' && parameters in listOf("1", "2", "3", "4", "5", "6", "7", "8") ->
                "\u001b[$parameters;${modifier}~"
            else -> input
        }
    }
    val output = if (ctrl && !input.startsWith('\u001b')) buildString(input.length) {
        input.forEach { char ->
            append(when {
                char in 'a'..'z' || char in 'A'..'Z' -> (char.uppercaseChar().code - 64).toChar()
                char == ' ' || char == '@' -> '\u0000'
                char in '['..'_' -> (char.code - 64).toChar()
                else -> char
            })
        }
    } else input
    return if (alt && !output.startsWith('\u001b')) "\u001b$output" else output
}
