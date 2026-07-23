package io.nekohasekai.sagernet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSessionGuardTest {
    @Test
    fun failedBindCanRetryWithoutPublishingIdleOverRunningVpn() {
        val guard = ConnectionSessionGuard()
        val repository = ProcessConnectionStateRepository()
        val runningVpn = Any()
        val binderClient = Any()
        repository.publish(runningVpn, ConnectionState.Connected)

        val failedGeneration = requireNotNull(guard.begin())
        repository.beginBinding(binderClient)
        assertTrue(guard.fail(failedGeneration))
        repository.markDead(binderClient)

        assertFalse(guard.isActive)
        assertEquals(
            ConnectionProjection.Bound(ConnectionState.Connected),
            repository.projection,
        )
        assertFalse(repository.canStart)
        assertTrue(repository.canStop)

        val retryGeneration = requireNotNull(guard.begin())
        assertNotEquals(failedGeneration, retryGeneration)
        assertTrue(guard.isActive)
    }

    @Test
    fun failedBindWithoutAnotherSourceRemainsUnknownAndCannotStart() {
        val guard = ConnectionSessionGuard()
        val repository = ProcessConnectionStateRepository()
        val binderClient = Any()

        val generation = requireNotNull(guard.begin())
        repository.beginBinding(binderClient)
        assertTrue(guard.fail(generation))
        repository.markDead(binderClient)

        assertEquals(ConnectionProjection.Dead, repository.projection)
        assertFalse(repository.canStart)
        assertFalse(repository.canStop)
    }

    @Test
    fun staleBinderCallbackCannotOverwriteEndpointFromNewSession() {
        val guard = ConnectionSessionGuard()
        var endpoint: String? = null

        val oldGeneration = requireNotNull(guard.begin())
        assertTrue(guard.commitIfCurrent(oldGeneration) { endpoint = "old" })
        guard.close()

        val currentGeneration = requireNotNull(guard.begin())
        assertTrue(guard.commitIfCurrent(currentGeneration) { endpoint = "current" })
        assertFalse(guard.commitIfCurrent(oldGeneration) { endpoint = null })

        assertEquals("current", endpoint)
    }

    @Test
    fun delayedBindFailureCannotCloseNewerRetry() {
        val guard = ConnectionSessionGuard()
        val failedGeneration = requireNotNull(guard.begin())
        assertTrue(guard.fail(failedGeneration))
        val retryGeneration = requireNotNull(guard.begin())
        var retryClosed = false

        assertFalse(guard.fail(failedGeneration) { retryClosed = true })

        assertFalse(retryClosed)
        assertTrue(guard.commitIfCurrent(retryGeneration) {})
    }

    @Test
    fun callbackQueuedBeforeDisconnectCannotReviveEndpointAfterClose() {
        val guard = ConnectionSessionGuard()
        var endpoint: String? = "connected"

        val generation = requireNotNull(guard.begin())
        guard.close { endpoint = null }
        assertFalse(guard.commitIfCurrent(generation) { endpoint = "stale" })

        assertNull(endpoint)
        assertTrue(guard.begin() != null)
    }

    @Test
    fun remoteRegistrationCompletingAfterDisconnectMustBeCleanedUp() {
        val guard = ConnectionSessionGuard()
        val generation = requireNotNull(guard.begin())
        var registrationPublished = false
        var staleRegistrationCleaned = false

        guard.close()
        val accepted = guard.commitIfCurrent(generation) {
            registrationPublished = true
        }
        if (!accepted) staleRegistrationCleaned = true

        assertFalse(registrationPublished)
        assertTrue(staleRegistrationCleaned)
    }
}
