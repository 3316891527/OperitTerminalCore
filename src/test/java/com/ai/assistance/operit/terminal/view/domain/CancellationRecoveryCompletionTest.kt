package com.ai.assistance.operit.terminal.view.domain

import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.terminal.command.CommandCancellationRecovery
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.QueuedCommand
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.view.domain.shell.ShellPromptStream
import org.junit.Assert.*
import org.junit.Test

class CancellationRecoveryCompletionTest {
    private fun runningSession(): TerminalSessionData = TerminalSessionData(
        title = "test", currentDirectory = "/work $", initState = SessionInitState.READY,
    ).apply {
        currentExecutingCommand = CommandHistoryItem("running", "/work $", "sleep 60", "", true)
        currentCommandOutput.append("output before interrupt")
    }

    private fun arm(session: TerminalSessionData, token: String = "token"): CommandCancellationRecovery =
        CommandCancellationRecovery("running", token).also { recovery ->
            session.commandLifecycle.cancellingCommandId = recovery.commandId
            session.commandLifecycle.cancellationRecovery = recovery
            recovery.onInterruptDispatched()
            assertTrue(recovery.queueProbeAfterPrompt())
            recovery.onProbeDispatched()
        }

    @Test fun missingCompletionMarker_matchingReplyFinishesOnceAndPreservesSessionAndQueue() {
        val events = mutableListOf<CommandExecutionEvent>()
        val advanced = mutableListOf<String>()
        val processor = OutputProcessor(
            onCommandExecutionEvent = { events += it }, onCommandCompleted = { advanced += it },
        )
        val session = runningSession()
        val recovery = arm(session)
        session.commandQueue += QueuedCommand("next", "pwd")
        val stream = ShellPromptStream()
        val reply = "\u001b]777;operit-cancel;${recovery.token}\u0007"
        val current = session.copy()
        val parts = reply.map { it.toString() }.flatMap { stream.feed(it) }
        parts.filterIsInstance<ShellPromptStream.Part.CancellationAcknowledgement>().forEach {
            assertTrue(processor.finishCancellationRecovery(current, it.token))
        }
        assertFalse(processor.finishCancellationRecovery(session, recovery.token))
        val event = events.single()
        assertEquals("running", event.commandId)
        assertEquals("cancelled", event.terminationReason)
        assertNull(event.exitCode)
        assertEquals("output before interrupt", event.outputChunk)
        assertFalse(session.commandLifecycle.closed)
        assertEquals("/work $", session.currentDirectory)
        assertEquals(listOf("next"), session.commandQueue.map { it.id })
        assertEquals(listOf(session.id), advanced)
        assertNull(current.currentExecutingCommand)
        assertFalse(session.currentExecutingCommand!!.isExecuting)
        assertEquals("running", session.commandLifecycle.cancellingCommandId)
    }

    @Test fun previousRequestReply_cannotFinishNewCancellationOrNextCommand() {
        val events = mutableListOf<CommandExecutionEvent>()
        val processor = OutputProcessor(onCommandExecutionEvent = { events += it })
        val session = runningSession()
        arm(session, "old-token")
        val current = arm(session, "new-token")
        assertFalse(processor.finishCancellationRecovery(session, "old-token"))
        assertTrue(session.currentExecutingCommand!!.isExecuting)
        session.currentExecutingCommand = CommandHistoryItem("next", "$ ", "pwd", "", true)
        assertFalse(processor.finishCancellationRecovery(session, current.token))
        assertTrue(events.isEmpty())
        assertTrue(session.currentExecutingCommand!!.isExecuting)
    }

    @Test fun lateReplyAfterClose_doesNotDuplicateCompletionOrReviveSession() {
        val events = mutableListOf<CommandExecutionEvent>()
        val processor = OutputProcessor(onCommandExecutionEvent = { events += it })
        val session = runningSession()
        val recovery = arm(session)
        processor.finishClosedSession(session, "closed")
        assertFalse(processor.finishCancellationRecovery(session, recovery.token))
        assertTrue(session.commandLifecycle.closed)
        assertEquals("session_closed", events.single().terminationReason)
    }

    @Test fun unsolicitedReply_doesNotFinishUncancelledCommand() {
        val events = mutableListOf<CommandExecutionEvent>()
        val processor = OutputProcessor(onCommandExecutionEvent = { events += it })
        val session = runningSession()
        assertFalse(processor.finishCancellationRecovery(session, "token"))
        assertTrue(session.currentExecutingCommand!!.isExecuting)
        assertTrue(events.isEmpty())
    }
}
