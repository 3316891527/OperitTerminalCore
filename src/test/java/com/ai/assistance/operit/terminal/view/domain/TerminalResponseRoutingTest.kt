package com.ai.assistance.operit.terminal.view.domain

import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.QueuedCommand
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalResponseRoutingTest {
    @Test fun repliesStayWithTheSessionThatProducedTheQuery() {
        val replies = mutableListOf<Pair<String, String>>()
        val processor = OutputProcessor(onTerminalResponse = { id, text -> replies += id to text })
        val first = TerminalSessionData(title = "first")
        val second = TerminalSessionData(title = "second")
        processor.processAnsiOutput(first, "\u001b[2;3H\u001b[6n")
        processor.processAnsiOutput(second, "\u001b[4;5H\u001b[6n")
        assertEquals(listOf(first.id to "\u001b[2;3R", second.id to "\u001b[4;5R"), replies)
    }

    @Test fun incompleteQueryInOneSessionDoesNotConsumeAnotherSessionsOutput() {
        val replies = mutableListOf<Pair<String, String>>()
        val processor = OutputProcessor(onTerminalResponse = { id, text -> replies += id to text })
        val first = TerminalSessionData(title = "first")
        val second = TerminalSessionData(title = "second")
        processor.processAnsiOutput(first, "\u001b[6")
        processor.processAnsiOutput(second, "\u001b[5n")
        assertEquals(listOf(second.id to "\u001b[0n"), replies)
        processor.processAnsiOutput(first, "n")
        assertEquals(listOf(second.id to "\u001b[0n", first.id to "\u001b[1;1R"), replies)
    }

    @Test fun protocolReplyPreservesTheWaitingPromptAndRunningCommand() {
        val replies = mutableListOf<Pair<String, String>>()
        val completions = mutableListOf<String>()
        val processor = OutputProcessor(
            onTerminalResponse = { id, text -> replies += id to text },
            onCommandCompleted = { completions += it },
        )
        val session = TerminalSessionData(
            title = "waiting", initState = SessionInitState.READY,
            isWaitingForInteractiveInput = true, isInteractiveMode = true,
            interactivePrompt = "Continue? (y/N)",
        ).apply {
            currentExecutingCommand = CommandHistoryItem("running", "$ ", "interactive-command", "", true)
            commandQueue += QueuedCommand("next", "pwd")
        }
        processor.processAnsiOutput(session, "\u001b[6n")
        assertEquals(listOf(session.id to "\u001b[1;1R"), replies)
        assertTrue(session.isWaitingForInteractiveInput)
        assertTrue(session.isInteractiveMode)
        assertEquals("Continue? (y/N)", session.interactivePrompt)
        assertTrue(session.currentExecutingCommand!!.isExecuting)
        assertEquals(listOf("next"), session.commandQueue.map { it.id })
        assertTrue(completions.isEmpty())
    }
}
