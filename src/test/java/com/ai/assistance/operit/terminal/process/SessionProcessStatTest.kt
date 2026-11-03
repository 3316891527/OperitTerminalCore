package com.ai.assistance.operit.terminal.process

import org.junit.Assert.*
import org.junit.Test

class SessionProcessStatTest {
    @Test fun parsesIdentityWithParenthesesAndSpacesInCommandName() {
        val fields = MutableList(20) { "0" }
        fields[0] = "S"
        fields[3] = "42"
        fields[19] = "987654"
        val result = parseSessionProcessStat("42 (bash (worker) task) " + fields.joinToString(" "))
        assertEquals(SessionProcessIdentity(42, 42, 987654L, "S"), result)
    }

    @Test fun incompleteOrMalformedStat_isNotAValidIdentity() {
        assertNull(parseSessionProcessStat("42 (bash) S 1"))
        assertNull(parseSessionProcessStat("invalid"))
    }
}
