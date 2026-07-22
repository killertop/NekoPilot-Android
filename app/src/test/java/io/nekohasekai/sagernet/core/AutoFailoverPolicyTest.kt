package io.nekohasekai.sagernet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoFailoverPolicyTest {

    @Test
    fun choosesLowestLatencyPreviouslySuccessfulBackup() {
        val candidates = listOf(
            SubscriptionDataCore.FailoverCandidate(1, 1, 20),
            SubscriptionDataCore.FailoverCandidate(2, 1, 90),
            SubscriptionDataCore.FailoverCandidate(3, 3, 0),
            SubscriptionDataCore.FailoverCandidate(4, 1, 55),
        )

        assertEquals(
            4L,
            SubscriptionDataCore.selectFailoverCandidate(1, candidates),
        )
    }

    @Test
    fun neverReturnsCurrentFailedOrAlreadyAttemptedNode() {
        val candidates = listOf(
            SubscriptionDataCore.FailoverCandidate(1, 1, 20),
            SubscriptionDataCore.FailoverCandidate(2, 1, 30),
            SubscriptionDataCore.FailoverCandidate(3, 1, 40),
        )

        assertEquals(
            3L,
            SubscriptionDataCore.selectFailoverCandidate(
                currentId = 1,
                candidates = candidates,
                excludedIds = setOf(2),
            ),
        )
        assertNull(
            SubscriptionDataCore.selectFailoverCandidate(
                currentId = 1,
                candidates = candidates,
                excludedIds = setOf(2, 3),
            ),
        )
    }

    @Test
    fun equalLatencyUsesStableProfileIdOrder() {
        val candidates = listOf(
            SubscriptionDataCore.FailoverCandidate(9, 1, 100),
            SubscriptionDataCore.FailoverCandidate(3, 1, 50),
            SubscriptionDataCore.FailoverCandidate(2, 1, 50),
        )

        assertEquals(2L, SubscriptionDataCore.selectFailoverCandidate(9, candidates))
    }
}
