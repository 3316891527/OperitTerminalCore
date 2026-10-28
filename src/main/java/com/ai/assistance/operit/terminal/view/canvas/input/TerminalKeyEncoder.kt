package com.ai.assistance.operit.terminal.view.canvas.input

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** 将软键盘按键事件与实体按键统一转换成 PTY 接受的字符。 */
internal class TerminalKeyEncoder {
    private var pendingAccent = 0

    fun reset() {
        pendingAccent = 0
    }

    fun encode(event: KeyEvent, applicationCursorKeys: Boolean): String? {
        val altGr = event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0
        val ctrl = event.isCtrlPressed && !altGr
        val alt = event.isAltPressed && !altGr
        val modifier = 1 + (if (event.isShiftPressed) 1 else 0) +
            (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

        fun cursor(final: Char): String = when {
            modifier != 1 -> "\u001b[1;${modifier}$final"
            applicationCursorKeys -> "\u001bO$final"
            else -> "\u001b[$final"
        }
        fun tilde(number: Int): String = if (modifier == 1) {
            "\u001b[$number~"
        } else {
            "\u001b[$number;${modifier}~"
        }
        fun character(value: String): String = if (alt) "\u001b$value" else value

        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return character("\r")
            KeyEvent.KEYCODE_DEL -> return character("\u007f")
            KeyEvent.KEYCODE_FORWARD_DEL -> return tilde(3)
            KeyEvent.KEYCODE_TAB -> return if (event.isShiftPressed) "\u001b[Z" else character("\t")
            KeyEvent.KEYCODE_ESCAPE -> return "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> return cursor('A')
            KeyEvent.KEYCODE_DPAD_DOWN -> return cursor('B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> return cursor('C')
            KeyEvent.KEYCODE_DPAD_LEFT -> return cursor('D')
            KeyEvent.KEYCODE_MOVE_HOME -> return cursor('H')
            KeyEvent.KEYCODE_MOVE_END -> return cursor('F')
            KeyEvent.KEYCODE_INSERT -> return tilde(2)
            KeyEvent.KEYCODE_PAGE_UP -> return tilde(5)
            KeyEvent.KEYCODE_PAGE_DOWN -> return tilde(6)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F4 -> {
                val final = ('P'.code + event.keyCode - KeyEvent.KEYCODE_F1).toChar()
                return if (modifier == 1) "\u001bO$final" else "\u001b[1;${modifier}$final"
            }
            in KeyEvent.KEYCODE_F5..KeyEvent.KEYCODE_F12 -> {
                val numbers = intArrayOf(15, 17, 18, 19, 20, 21, 23, 24)
                return tilde(numbers[event.keyCode - KeyEvent.KEYCODE_F5])
            }
        }

        // Ctrl 的字符映射不依赖 Android 是否已经把 unicodeChar 转成控制字符。
        val textMeta = event.metaState and KeyEvent.META_CTRL_MASK.inv() and
            (if (alt) KeyEvent.META_ALT_MASK.inv() else -1)
        var codePoint = event.getUnicodeChar(textMeta)
        if (codePoint == 0) return null
        if (codePoint and KeyCharacterMap.COMBINING_ACCENT != 0) {
            pendingAccent = codePoint and KeyCharacterMap.COMBINING_ACCENT_MASK
            return ""
        }
        if (ctrl) {
            val control = when (val char = codePoint.toChar()) {
                in 'a'..'z' -> char.code - 'a'.code + 1
                in '@'..'_' -> char.code - '@'.code
                ' ', '2' -> 0
                '3' -> 27
                '4' -> 28
                '5' -> 29
                '6' -> 30
                '7', '/' -> 31
                '8', '?' -> 127
                else -> codePoint
            }
            pendingAccent = 0
            return character(String(Character.toChars(control)))
        }
        if (pendingAccent != 0) {
            val accent = pendingAccent
            pendingAccent = 0
            val combined = KeyCharacterMap.getDeadChar(accent, codePoint)
            if (combined != 0) {
                codePoint = combined
            } else {
                return character(String(Character.toChars(accent)) + String(Character.toChars(codePoint)))
            }
        }
        return character(String(Character.toChars(codePoint)))
    }
}
