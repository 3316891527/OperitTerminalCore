package com.ai.assistance.operit.terminal.view.domain.shell

import com.ai.assistance.operit.terminal.command.CommandCancellationRecovery
import org.junit.Assert.*
import org.junit.Test

class ShellPromptStreamTest {
    private fun prompt(directory: String) = "\u001b]777;operit-prompt;$directory\u0007"
    private fun ack(token: String) = "\u001b]777;operit-cancel;$token\u0007"

    @Test fun cancellationAcknowledgement_survivesEveryPossibleChunkBoundary() {
        val marker = ack("token")
        for (split in 0..marker.length) {
            val stream = ShellPromptStream()
            val parts = stream.feed(marker.take(split)) + stream.feed(marker.drop(split))
            assertEquals(listOf(ShellPromptStream.Part.CancellationAcknowledgement("token")), parts)
            assertFalse(stream.hasPromptProtocol)
        }
    }

    @Test fun promptAndReply_surviveEveryBoundaryWithoutChangingDirectoryProtocol() {
        val markers = prompt("/work%25dir") + ack("token")
        for (split in 0..markers.length) {
            val stream = ShellPromptStream()
            val parts = stream.feed(markers.take(split)) + stream.feed(markers.drop(split))
            assertEquals(listOf(
                ShellPromptStream.Part.Prompt("/work%dir"),
                ShellPromptStream.Part.CancellationAcknowledgement("token"),
            ), parts)
            assertTrue(stream.hasPromptProtocol)
        }
    }

    @Test fun mixedOutputPromptAndAcknowledgement_preserveOrderAndDirectoryEscapes() {
        val stream = ShellPromptStream()
        val parts = stream.feed("before" + prompt("/work%25dir%0Aline") + ack("token") + "after")
        assertEquals(listOf(
            ShellPromptStream.Part.Output("before"),
            ShellPromptStream.Part.Prompt("/work%dir\nline"),
            ShellPromptStream.Part.CancellationAcknowledgement("token"),
            ShellPromptStream.Part.Output("after"),
        ), parts)
        assertTrue(stream.hasPromptProtocol)
    }

    @Test fun probeInputEcho_isOrdinaryTextAndCannotAcknowledgeRecovery() {
        val stream = ShellPromptStream()
        val echo = CommandCancellationRecovery("command", "token").probeInput
        assertEquals(listOf(ShellPromptStream.Part.Output(echo)), stream.feed(echo))
        assertFalse(stream.hasPromptProtocol)
    }

    @Test fun sharedPartialPrefix_doesNotSwallowOrdinaryOutputOrCompletionMarker() {
        val stream = ShellPromptStream()
        val partial = "\u001b]777;operit-"
        assertTrue(stream.feed(partial).isEmpty())
        val output = "other\u0007\n__OPERIT_COMMAND_DONE__:command:7\n"
        assertEquals(listOf(ShellPromptStream.Part.Output(partial + output)), stream.feed(output))
    }
}
