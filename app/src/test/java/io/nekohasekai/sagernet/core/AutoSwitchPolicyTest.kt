package io.nekohasekai.sagernet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSwitchPolicyTest {

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
                results = mapOf(1L to 100, 2L to 71),
            )
        )
        assertEquals(
            2L,
            SubscriptionDataCore.selectMeaningfullyFaster(
                selectedId = 1L,
                results = mapOf(1L to 100, 2L to 70),
            )?.profileId,
        )
    }

    @Test
    fun percentageThresholdDominatesOnHighLatencyNodes() {
        assertNull(
            SubscriptionDataCore.selectMeaningfullyFaster(
                selectedId = 1L,
                results = mapOf(1L to 400, 2L to 345),
            )
        )
        assertEquals(
            2L,
            SubscriptionDataCore.selectMeaningfullyFaster(
                selectedId = 1L,
                results = mapOf(1L to 400, 2L to 340),
            )?.profileId,
        )
    }

    @Test
    fun confirmationMustChooseSameCandidateTwice() {
        val first = SubscriptionDataCore.selectMeaningfullyFaster(
            selectedId = 1L,
            results = mapOf(1L to 200, 2L to 80, 3L to 90),
        )
        assertNull(
            SubscriptionDataCore.confirmAutoSwitch(
                first,
                selectedId = 1L,
                confirmationResults = mapOf(1L to 200, 2L to 90, 3L to 70),
            )
        )
        assertEquals(
            2L,
            SubscriptionDataCore.confirmAutoSwitch(
                first,
                selectedId = 1L,
                confirmationResults = mapOf(1L to 200, 2L to 75, 3L to 95),
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
