package com.ai.assistance.operit.terminal.command

import org.junit.Assert.*
import org.junit.Test

class CommandCancellationRecoveryTest {
    @Test fun promptBeforeInterrupt_doesNotQueueProbeOrFinishCommand() {
        val recovery = CommandCancellationRecovery("command", "token")
        assertFalse(recovery.queueProbeAfterPrompt())
        assertFalse(recovery.acceptsAcknowledgement("token"))
    }

    @Test fun promptAfterInterrupt_requiresDispatchedProbeAndMatchingToken() {
        val recovery = CommandCancellationRecovery("command", "token")
        recovery.onInterruptDispatched()
        assertTrue(recovery.queueProbeAfterPrompt())
        assertFalse(recovery.queueProbeAfterPrompt())
        assertFalse(recovery.acceptsAcknowledgement("token"))
        recovery.onProbeDispatched()
        assertFalse(recovery.acceptsAcknowledgement("old-token"))
        assertTrue(recovery.acceptsAcknowledgement("token"))
    }

    @Test fun laterPrompt_canQueueNewProbeAfterEarlierInputWasFlushed() {
        val recovery = CommandCancellationRecovery("command", "token")
        recovery.onInterruptDispatched()
        assertTrue(recovery.queueProbeAfterPrompt())
        recovery.onProbeDispatched()
        assertTrue(recovery.queueProbeAfterPrompt())
        assertFalse(recovery.queueProbeAfterPrompt())
    }

    @Test fun separateCancellationRequests_haveDifferentTokensEvenForSameCommandId() {
        val first = CommandCancellationRecovery("command")
        val second = CommandCancellationRecovery("command")
        assertNotEquals(first.token, second.token)
        assertFalse(first.probeInput.contains('\u001b'))
        assertFalse(first.probeInput.contains('\u0007'))
        assertTrue(first.probeInput.endsWith("\r"))
    }
}
