package io.nekohasekai.sagernet.utils

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeasePublicationGateTest {
    @Test
    fun destroyBeforeStartReturnsRejectsLateLease() {
        val gate = LeasePublicationGate<FakeLease>()
        val startGeneration = gate.open().token

        assertNull(gate.invalidate(startGeneration))
        val lateLease = FakeLease()
        val publication = gate.publish(startGeneration, lateLease)
        if (!publication.accepted) lateLease.close()

        assertFalse(publication.accepted)
        assertFalse(gate.isCurrent(startGeneration))
        assertTrue(lateLease.closed.get() == 1)
    }

    @Test
    fun oldLifecycleInvalidationCannotDetachNewLease() {
        val gate = LeasePublicationGate<FakeLease>()
        val oldGeneration = gate.open().token
        val newGeneration = gate.open().token
        val newLease = FakeLease()
        assertTrue(gate.publish(newGeneration, newLease).accepted)

        assertNull(gate.invalidate(oldGeneration))
        assertTrue(gate.isCurrent(newGeneration))
        assertTrue(newLease.closed.get() == 0)
        assertTrue(gate.invalidate(newGeneration) === newLease)
    }

    @Test
    fun repeatedStartAtomicallyDisplacesPriorLease() {
        val gate = LeasePublicationGate<FakeLease>()
        val first = gate.open().token
        val firstLease = FakeLease()
        assertTrue(gate.publish(first, firstLease).accepted)

        val second = gate.open()

        assertTrue(second.displaced === firstLease)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second.token))
    }

    private class FakeLease : AutoCloseable {
        val closed = AtomicInteger()
        override fun close() {
            closed.incrementAndGet()
        }
    }
}
