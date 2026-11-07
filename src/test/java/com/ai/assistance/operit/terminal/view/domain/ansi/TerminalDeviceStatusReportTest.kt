package com.ai.assistance.operit.terminal.view.domain.ansi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalDeviceStatusReportTest {
    @Test fun cursorReportUsesOneBasedCoordinatesWithoutRenderingTheReply() {
        val emulator = AnsiTerminalEmulator(80, 24)
        val replies = emulator.parseWithResponses("\u001b[3;7H\u001b[6n")
        assertEquals(listOf("\u001b[3;7R"), replies)
        assertTrue(emulator.getScreenContent().all { row -> row.all { it.char == ' ' } })
    }

    @Test fun operatingStatusAndPrivateCursorReportUseTheirProtocolPrefixes() {
        val emulator = AnsiTerminalEmulator(80, 24)
        assertEquals(
            listOf("\u001b[0n", "\u001b[?2;4R"),
            emulator.parseWithResponses("\u001b[5n\u001b[2;4H\u001b[?6n"),
        )
    }

    @Test fun unrelatedOrMalformedStatusRequestsDoNotEmitAReply() {
        val emulator = AnsiTerminalEmulator(80, 24)
        assertTrue(emulator.parseWithResponses(
            "\u001b[?5n\u001b[n\u001b[7n\u001b[6;0n\u001b[6 n",
        ).isEmpty())
    }

    @Test fun splitQueryOnlyRepliesOnceAfterTheSequenceCompletes() {
        val query = "\u001b[6n"
        for (split in 0..query.length) {
            val emulator = AnsiTerminalEmulator(80, 24)
            emulator.parse("\u001b[4;9H")
            val replies = emulator.parseWithResponses(query.take(split)) +
                emulator.parseWithResponses(query.drop(split))
            assertEquals("split=$split", listOf("\u001b[4;9R"), replies)
            assertTrue(emulator.parseWithResponses("").isEmpty())
        }
    }

    @Test fun characterByCharacterQueryRetainsItsPrivatePrefix() {
        val emulator = AnsiTerminalEmulator(80, 24)
        val replies = "\u001b[?6n".flatMap { emulator.parseWithResponses(it.toString()) }
        assertEquals(listOf("\u001b[?1;1R"), replies)
    }

    @Test fun multipleQueriesCaptureTheirOwnPositionsBeforeLaterOutputMovesTheCursor() {
        val emulator = AnsiTerminalEmulator(80, 24)
        assertEquals(
            listOf("\u001b[2;3R", "\u001b[7;11R"),
            emulator.parseWithResponses("\u001b[2;3H\u001b[6n\u001b[7;11H\u001b[6n\u001b[1;1H"),
        )
    }

    @Test fun pendingAutowrapReportsLastColumnAndThenTheWrappedPosition() {
        val emulator = AnsiTerminalEmulator(4, 3)
        assertEquals(
            listOf("\u001b[1;4R", "\u001b[2;2R"),
            emulator.parseWithResponses("abcd\u001b[6nX\u001b[6n"),
        )
    }

    @Test fun originModeReportsRowsRelativeToTheScrollingRegion() {
        val emulator = AnsiTerminalEmulator(80, 24)
        assertEquals(
            listOf("\u001b[3;7R", "\u001b[?3;7R"),
            emulator.parseWithResponses("\u001b[5;12r\u001b[?6h\u001b[3;7H\u001b[6n\u001b[?6n"),
        )
    }

    @Test fun surveySizeProbeReportsClampedDimensionsAndRestoresThePromptCursor() {
        val emulator = AnsiTerminalEmulator(80, 24)
        val replies = emulator.parseWithResponses(
            "\u001b[2;10H\u001b[?25l\u001b7\u001b[999;999f\u001b[6n" +
                "\u001b8\u001b[?25h\u001b[6n",
        )
        assertEquals(listOf("\u001b[24;80R", "\u001b[2;10R"), replies)
        assertTrue(emulator.isCursorVisible())
    }

    @Test fun statusQueryDoesNotChangeProgramControlledCursorVisibility() {
        val emulator = AnsiTerminalEmulator(80, 24)
        emulator.parse("\u001b[?25l")
        assertEquals(listOf("\u001b[1;1R"), emulator.parseWithResponses("\u001b[6n"))
        assertFalse(emulator.isCursorVisible())
    }

    @Test fun controlStringContentsAndPlainInputDoNotTriggerProtocolReplies() {
        val emulator = AnsiTerminalEmulator(80, 24)
        assertTrue(emulator.parseWithResponses("\u001b]0;title \u001b[6n\u0007y/n").isEmpty())
    }
}
