package io.nekohasekai.sagernet.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkIdentityTrackerTest {

    @Test
    fun `capability updates for the same network preserve retry backoff`() {
        val tracker = DefaultNetworkIdentityTracker<NetworkIdentity>()
        val network = NetworkIdentity(100)

        assertTrue(tracker.shouldForceRetry(network))
        assertFalse(tracker.shouldForceRetry(network))
        assertFalse(tracker.shouldForceRetry(NetworkIdentity(100)))
    }

    @Test
    fun `new or restored network permits an immediate retry`() {
        val tracker = DefaultNetworkIdentityTracker<NetworkIdentity>()
        val first = NetworkIdentity(100)
        val second = NetworkIdentity(101)

        assertTrue(tracker.shouldForceRetry(first))
        assertTrue(tracker.shouldForceRetry(second))
        assertFalse(tracker.shouldForceRetry(null))
        assertTrue(tracker.shouldForceRetry(second))
    }

    private data class NetworkIdentity(val id: Int)
}
