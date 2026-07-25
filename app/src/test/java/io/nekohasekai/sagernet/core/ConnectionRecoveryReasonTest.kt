package io.nekohasekai.sagernet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionRecoveryReasonTest {
    @Test
    fun persistedReasonsRoundTripWithoutRelyingOnEnumOrder() {
        ConnectionRecoveryReason.entries.forEach { reason ->
            assertEquals(reason, ConnectionRecoveryReason.fromPersisted(reason.persistedValue))
        }
    }

    @Test
    fun unknownOrBlankPersistedReasonsAreIgnored() {
        assertNull(ConnectionRecoveryReason.fromPersisted(null))
        assertNull(ConnectionRecoveryReason.fromPersisted(""))
        assertNull(ConnectionRecoveryReason.fromPersisted("future_reason"))
    }
}
