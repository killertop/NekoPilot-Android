package io.nekohasekai.sagernet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSwitchPolicyTest {

    @Test
    fun oldNodeMustBeFullyDrainedBeforeSwitch() {
        val oldTag = "node-11"
        assertTrue(
            SubscriptionDataCore.hasActiveConnectionsOnNode(
                oldTag,
                listOf(setOf("tun-in", "proxy", oldTag), setOf("direct")),
            )
        )
        assertFalse(
            SubscriptionDataCore.hasActiveConnectionsOnNode(
                oldTag,
                listOf(setOf("tun-in", "proxy", "node-22"), setOf("direct")),
            )
        )
    }
    @Test
    fun selectsWorkingCandidateWhenCurrentNodeFails() {
        val decision = SubscriptionDataCore.selectMeaningfullyFaster(
            selectedId = 1L,
            results = mapOf(1L to 0, 2L to 80, 3L to 60),
        )
        assertEquals(3L, decision?.profileId)
        assertEquals(60, decision?.latencyMs)
        assertNull(decision?.currentLatencyMs)
    }

    @Test
    fun requiresMeaningfulLatencyImprovement() {
        assertNull(
            SubscriptionDataCore.selectMeaningfullyFaster(
                selectedId = 1L,
                results = mapOf(1L to 100, 2L to 85),
            )
        )
        assertEquals(
            2L,
            SubscriptionDataCore.selectMeaningfullyFaster(
                selectedId = 1L,
                results = mapOf(1L to 100, 2L to 79),
            )?.profileId,
        )
    }

    @Test
    fun deterministicTieUsesLowerProfileId() {
        assertEquals(
            2L,
            SubscriptionDataCore.selectMeaningfullyFaster(
                selectedId = 9L,
                results = mapOf(9L to 200, 3L to 50, 2L to 50),
            )?.profileId,
        )
    }
}
