package com.ai.assistance.operit.terminal.process

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalExitMonitorTest {
    @Test fun descendantsHoldingOutputOpen_doNotPreventExitNotification() = runTest {
        var alive = true
        val reader = backgroundScope.launch { awaitCancellation() }
        val monitor = async { awaitTerminalExit({ alive }, reader, pollMs = 10L, drainMs = 50L) }
        runCurrent()
        alive = false
        advanceTimeBy(60L)
        runCurrent()
        assertFalse(monitor.await())
        assertTrue(reader.isActive)
    }

    @Test fun drainedReader_finishesWithoutWaitingFullDrainDeadline() = runTest {
        val reader = launch { }
        reader.join()
        assertTrue(awaitTerminalExit({ false }, reader, drainMs = 1_000L))
    }
}
