package com.ai.assistance.operit.terminal.view.canvas.input

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo

/** 输入法仅在本地保存未定稿文字，定稿后一次写入终端，避免候选词重复回显。 */
internal class TerminalInputConnection(
    private val target: View,
    private val onInput: (String) -> Unit
) : BaseInputConnection(target, true) {
    private var closed = false
    private val composingText = SpannableStringBuilder().apply {
        Selection.setSelection(this, 0)
    }

    override fun getEditable(): Editable = composingText

    fun takePendingText(): String {
        val text = normalize(composingText.toString())
        composingText.clear()
        removeComposingSpans(composingText)
        Selection.setSelection(composingText, 0)
        return text
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed) return false
        takePendingText()
        val committed = normalize(text?.toString().orEmpty())
        if (committed.isNotEmpty()) onInput(committed)
        return true
    }

    override fun finishComposingText(): Boolean {
        if (closed) return false
        val text = takePendingText()
        if (text.isNotEmpty()) onInput(text)
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        if (closed) return false
        return when (editorAction and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_UNSPECIFIED,
            EditorInfo.IME_ACTION_NONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS -> {
                // 将定稿和回车一起提交，交互程序只收到一次确认。
                onInput(takePendingText() + "\r")
                true
            }
            else -> false
        }
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean = !closed && target.dispatchKeyEvent(event)

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        deleteText(beforeLength, afterLength, codePoints = false)

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteText(beforeLength, afterLength, codePoints = true)

    private fun deleteText(before: Int, after: Int, codePoints: Boolean): Boolean {
        if (closed || before < 0 || after < 0) return false
        val selectionStart = Selection.getSelectionStart(composingText).coerceAtLeast(0)
        val selectionEnd = Selection.getSelectionEnd(composingText).coerceAtLeast(0)
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        val availableBefore = if (codePoints) {
            Character.codePointCount(composingText, 0, start)
        } else start
        val availableAfter = if (codePoints) {
            Character.codePointCount(composingText, end, composingText.length)
        } else composingText.length - end
        val localBefore = minOf(before, availableBefore)
        val localAfter = minOf(after, availableAfter)
        val deleteStart = if (codePoints) {
            Character.offsetByCodePoints(composingText, start, -localBefore)
        } else start - localBefore
        val deleteEnd = if (codePoints) {
            Character.offsetByCodePoints(composingText, end, localAfter)
        } else end + localAfter
        composingText.delete(end, deleteEnd)
        composingText.delete(deleteStart, start)
        val terminalDeletes = "\u007f".repeat(before - localBefore) +
            "\u001b[3~".repeat(after - localAfter)
        if (terminalDeletes.isNotEmpty()) onInput(terminalDeletes)
        return true
    }

    override fun closeConnection() {
        if (closed) return
        takePendingText()
        closed = true
        super.closeConnection()
    }

    private fun normalize(text: String): String = text.replace("\r\n", "\r").replace('\n', '\r')
}
