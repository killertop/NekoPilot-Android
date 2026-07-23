package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeTestOutcomeTest {
    @Test
    fun failureClearsPreviousLatencyAndDownloadSpeed() {
        val entity = ProxyEntity(status = 1, ping = 42, error = null).also {
            it.downloadMbps = 12.5
        }

        entity.applyNodeTestOutcome(
            NodeTestOutcome.Unavailable(
                NodeTestOutcome.FailureReason.TEST_FAILED,
                "timeout",
            ),
        )

        assertEquals(3, entity.status)
        assertEquals(0, entity.ping)
        assertNull(entity.downloadMbps)
        assertEquals("timeout", entity.error)
    }

    @Test
    fun successClearsPreviousError() {
        val entity = ProxyEntity(status = 3, error = "old failure")

        entity.applyNodeTestOutcome(NodeTestOutcome.Available(87, 4.5))

        assertEquals(1, entity.status)
        assertEquals(87, entity.ping)
        assertEquals(4.5, entity.downloadMbps)
        assertNull(entity.error)
    }

    @Test
    fun resetClearsEveryRuntimeResultField() {
        val entity = ProxyEntity(status = 2, ping = 3, error = "missing").also {
            it.downloadMbps = 1.0
        }

        entity.clearNodeTestOutcome()

        assertEquals(0, entity.status)
        assertEquals(0, entity.ping)
        assertNull(entity.downloadMbps)
        assertNull(entity.error)
    }
}
