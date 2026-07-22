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
                results = mapOf(1L to 100, 2L to 51),
            )
        )
        assertEquals(
            2L,
            SubscriptionDataCore.selectMeaningfullyFaster(
                selectedId = 1L,
                results = mapOf(1L to 100, 2L to 50),
            )?.profileId,
        )
    }

    @Test
    fun percentageThresholdDominatesOnHighLatencyNodes() {
        assertNull(
            SubscriptionDataCore.selectMeaningfullyFaster(
                selectedId = 1L,
                results = mapOf(1L to 400, 2L to 321),
            )
        )
        assertEquals(
            2L,
            SubscriptionDataCore.selectMeaningfullyFaster(
                selectedId = 1L,
                results = mapOf(1L to 400, 2L to 320),
            )?.profileId,
        )
    }

    @Test
    fun confirmationUsesStableWorstCaseAcrossBothBatches() {
        val firstResults = mapOf(1L to 200, 2L to 80, 3L to 90)
        val confirmationResults = mapOf(1L to 210, 2L to 90, 3L to 70)
        val first = SubscriptionDataCore.selectMeaningfullyFaster(
            selectedId = 1L,
            results = firstResults,
        )
        val stableResults = SubscriptionDataCore.stableAutoSwitchResults(
            selectedId = 1L,
            firstResults = firstResults,
            confirmationResults = confirmationResults,
        )
        assertEquals(mapOf(1L to 200, 2L to 90, 3L to 90), stableResults)
        val confirmed = SubscriptionDataCore.confirmAutoSwitch(
            first = first,
            selectedId = 1L,
            firstResults = firstResults,
            confirmationResults = confirmationResults,
        )
        assertEquals(2L, confirmed?.profileId)
        assertEquals(
            confirmed?.profileId,
            stableResults.entries.sortedWith(compareBy({ it.value }, { it.key })).first().key,
        )
    }

    @Test
    fun confirmationRejectsLuckyOrUnavailableCandidate() {
        val firstResults = mapOf(1L to 150, 2L to 50, 3L to 70)
        val first = SubscriptionDataCore.selectMeaningfullyFaster(1L, firstResults)
        assertNull(
            SubscriptionDataCore.confirmAutoSwitch(
                first = first,
                selectedId = 1L,
                firstResults = firstResults,
                confirmationResults = mapOf(1L to 150, 2L to 120),
            ),
        )
        assertEquals(
            3L,
            SubscriptionDataCore.confirmAutoSwitch(
                first = first,
                selectedId = 1L,
                firstResults = firstResults,
                confirmationResults = mapOf(1L to 150, 3L to 70),
            )?.profileId,
        )
    }

    @Test
    fun confirmationDoesNotUseTransientCurrentNodeSlowdownAsSwitchEvidence() {
        val firstResults = mapOf(1L to 400, 2L to 100)
        val first = SubscriptionDataCore.selectMeaningfullyFaster(1L, firstResults)
        assertNull(
            SubscriptionDataCore.confirmAutoSwitch(
                first = first,
                selectedId = 1L,
                firstResults = firstResults,
                confirmationResults = mapOf(1L to 80, 2L to 100),
            ),
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
