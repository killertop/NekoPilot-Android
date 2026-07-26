package io.nekohasekai.sagernet.location

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationNetworkListenerGateTest {
    @Test
    fun disableEnableRejectsOldLateStartWithoutPollutingIdentity() {
        val gate = GenerationNetworkListenerGate<FakeLease, String>()
        assertNull(gate.configure(generation = 1L, enabled = true))
        val oldStart = checkNotNull(gate.reserveStart(1L))

        assertNull(gate.configure(generation = 2L, enabled = false))
        assertNull(gate.configure(generation = 3L, enabled = true))
        val newStart = checkNotNull(gate.reserveStart(3L))
        val oldLease = FakeLease()
        val oldAccepted = gate.publish(oldStart, oldLease)
        assertFalse(oldAccepted)
        if (!oldAccepted) oldLease.close()

        assertFalse(gate.shouldForceRetry(oldStart, "wifi"))
        assertTrue(gate.shouldForceRetry(newStart, "wifi"))
        assertFalse(gate.shouldForceRetry(newStart, "wifi"))
        assertTrue(gate.publish(newStart, FakeLease()))
        assertTrue(oldLease.closed.get() == 1)
    }

    @Test
    fun disableAtomicallyDetachesInstalledLeaseBeforeNewGeneration() {
        val gate = GenerationNetworkListenerGate<FakeLease, String>()
        gate.configure(generation = 10L, enabled = true)
        val oldStart = checkNotNull(gate.reserveStart(10L))
        val oldLease = FakeLease()
        assertTrue(gate.publish(oldStart, oldLease))

        assertTrue(gate.configure(generation = 11L, enabled = false) === oldLease)
        gate.configure(generation = 12L, enabled = true)
        val newStart = checkNotNull(gate.reserveStart(12L))
        val newLease = FakeLease()
        assertTrue(gate.publish(newStart, newLease))

        oldLease.close() // Delayed close is lease-identity scoped.
        assertFalse(gate.shouldForceRetry(oldStart, "cellular"))
        assertTrue(gate.shouldForceRetry(newStart, "cellular"))
        assertTrue(newLease.closed.get() == 0)
    }

    @Test
    fun failedStartClearsReservationForDeterministicRetry() {
        val gate = GenerationNetworkListenerGate<FakeLease, String>()
        gate.configure(generation = 20L, enabled = true)
        val failed = checkNotNull(gate.reserveStart(20L))
        gate.fail(failed)
        val retry = checkNotNull(gate.reserveStart(20L))

        assertFalse(gate.shouldForceRetry(failed, "wifi"))
        assertTrue(gate.shouldForceRetry(retry, "wifi"))
    }

    @Test
    fun oldRefreshCannotConsumeNewGenerationRetryRequest() {
        val gate = GenerationNetworkListenerGate<FakeLease, String>()
        gate.configure(generation = 30L, enabled = true)
        val oldRefreshGeneration = 30L

        gate.configure(generation = 31L, enabled = false)
        gate.configure(generation = 32L, enabled = true)
        assertTrue(gate.requestRetry(32L))

        assertFalse(gate.consumeRetry(oldRefreshGeneration))
        assertTrue(gate.consumeRetry(32L))
        assertFalse(gate.consumeRetry(32L))
    }

    private class FakeLease : AutoCloseable {
        val closed = AtomicInteger()
        override fun close() {
            closed.incrementAndGet()
        }
    }
}
