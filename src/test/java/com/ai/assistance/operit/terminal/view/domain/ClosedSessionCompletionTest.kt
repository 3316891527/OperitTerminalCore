package com.ai.assistance.operit.terminal.view.domain

import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.QueuedCommand
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import org.junit.Assert.*
import org.junit.Test

class ClosedSessionCompletionTest {
    @Test fun repeatedClose_notifiesRunningAndEveryQueuedCommandExactlyOnce() {
        val events = mutableListOf<CommandExecutionEvent>()
        val processor = OutputProcessor(onCommandExecutionEvent = { events += it })
        val session = TerminalSessionData(title = "test")
        session.currentExecutingCommand = CommandHistoryItem("running", "$ ", "sleep", "", true)
        session.currentCommandOutput.append("tail")
        session.commandQueue += listOf(QueuedCommand("q1", "true"), QueuedCommand("q2", "true"))
        processor.finishClosedSession(session, "closed", 9)
        processor.finishClosedSession(session.copy(), "closed", 9)
        assertEquals(listOf("running", "q1", "q2"), events.map { it.commandId })
        assertTrue(events.all { it.isCompleted && it.exitCode == null && it.terminationReason == "session_closed" })
        assertEquals(9, events.first().sessionExitCode)
        assertTrue(events.first().outputChunk.contains("tail"))
        assertTrue(session.commandQueue.isEmpty())
        assertNull(session.currentExecutingCommand)
    }
}
