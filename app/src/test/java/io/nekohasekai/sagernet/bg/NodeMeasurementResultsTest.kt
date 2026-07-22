package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.ProxyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeMeasurementResultsTest {

    @Test
    fun missingAndInvalidResultsClearPreviousSuccessfulLatency() {
        val candidates = listOf(
            ProxyEntity(id = 1, status = 1, ping = 42, configRevision = 11),
            ProxyEntity(id = 2, status = 1, ping = 55, configRevision = 22),
            ProxyEntity(id = 3, status = 1, ping = 68, configRevision = 33),
        )

        val completed = completeNodeMeasurements(
            candidates,
            successfulLatencies = mapOf(1L to 101, 2L to 0),
            failureMessage = "timeout",
        ).associateBy(ProxyEntity::id)

        assertEquals(1, completed.getValue(1).status)
        assertEquals(101, completed.getValue(1).ping)
        assertNull(completed.getValue(1).error)
        assertEquals(3, completed.getValue(2).status)
        assertEquals(0, completed.getValue(2).ping)
        assertEquals("timeout", completed.getValue(2).error)
        assertEquals(3, completed.getValue(3).status)
        assertEquals(0, completed.getValue(3).ping)
        assertEquals("timeout", completed.getValue(3).error)
    }

    @Test
    fun completionKeepsSnapshotRevisionForStaleWriteProtection() {
        val snapshot = ProxyEntity(id = 7, groupId = 8, type = 9, configRevision = 123)

        val completed = completeNodeMeasurements(
            listOf(snapshot),
            successfulLatencies = mapOf(7L to 88),
            failureMessage = "timeout",
        ).single()

        assertEquals(8, completed.groupId)
        assertEquals(9, completed.type)
        assertEquals(123, completed.configRevision)
    }
}
