package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.core.AutoNodeSelectionPhase
import io.nekohasekai.sagernet.core.AutoNodeSelectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeFailoverPolicyTest {
    private val firstEpoch = NodeFailureEpoch("node-1", stateRevision = 1L, networkGeneration = 1L)

    @Test
    fun requiresThreeSeparatedFailuresInsideWindowAndOnlineNetwork() {
        val policy = NodeFailoverPolicy()

        assertFalse(policy.recordRequestFailure(firstEpoch, 1_000, false))
        assertFalse(policy.recordRequestFailure(firstEpoch, 2_000, true))
        assertFalse(policy.recordRequestFailure(firstEpoch, 2_200, true))
        assertFalse(policy.recordRequestFailure(firstEpoch, 3_000, true))
        assertTrue(policy.recordRequestFailure(firstEpoch, 4_000, true))
        assertTrue(policy.beginRecovery(firstEpoch, 4_000))
        assertFalse(policy.beginRecovery(firstEpoch, 4_000))
    }

    @Test
    fun switchingExcludesFailedNodesForTheFailureRound() {
        val policy = recoveringPolicy(firstEpoch, now = 1_700)

        assertTrue(policy.markSwitched(firstEpoch, listOf(1L, 2L), now = 1_700))
        assertEquals(setOf(1L, 2L), policy.excludedProfileIds(1_701))
    }

    @Test
    fun aSuccessfulSwitchHonorsShortStabilizationWindow() {
        val policy = NodeFailoverPolicy(switchCooldownMs = 1_000, exhaustedCooldownMs = 5_000)
        recordThreshold(policy, firstEpoch, 100)
        assertTrue(policy.beginRecovery(firstEpoch, 1_700))
        assertTrue(policy.markSwitched(firstEpoch, listOf(1L), now = 1_700))
        assertFalse(policy.recordRequestFailure(firstEpoch, 2_000, true))

        val secondEpoch = NodeFailureEpoch("node-2", 2L, 1L)
        assertFalse(policy.recordRequestFailure(secondEpoch, 2_700, true))
        assertFalse(policy.recordRequestFailure(secondEpoch, 3_500, true))
        assertTrue(policy.recordRequestFailure(secondEpoch, 4_300, true))
        assertTrue(policy.beginRecovery(secondEpoch, 4_300))
    }

    @Test
    fun exhaustedRoundIsBlockedThenResetInsteadOfLooping() {
        val policy = NodeFailoverPolicy(exhaustedCooldownMs = 5_000, switchCooldownMs = 1_000)
        recordThreshold(policy, firstEpoch, 8_400)
        assertTrue(policy.beginRecovery(firstEpoch, 10_000))
        assertTrue(policy.markNoCandidate(firstEpoch, listOf(1L, 2L), now = 10_000))

        repeat(3) { index ->
            assertFalse(
                policy.recordRequestFailure(
                    firstEpoch,
                    11_000L + index * 1_000L,
                    true,
                ),
            )
        }
        assertTrue(policy.excludedProfileIds(15_000).isEmpty())
    }

    @Test
    fun networkAndNodeEpochChangesCannotCombineFailureEvidence() {
        val policy = NodeFailoverPolicy()
        assertFalse(policy.recordRequestFailure(firstEpoch, 1_000, true))
        assertFalse(policy.recordRequestFailure(firstEpoch, 2_000, true))

        val nextNetwork = firstEpoch.copy(networkGeneration = 2L)
        assertFalse(policy.recordRequestFailure(nextNetwork, 3_000, true))
        assertFalse(policy.recordRequestFailure(nextNetwork, 4_000, true))
        assertTrue(policy.recordRequestFailure(nextNetwork, 5_000, true))

        policy.resetForNetworkChange()
        assertFalse(policy.recordRequestFailure(nextNetwork, 6_000, true))
    }

    @Test
    fun healthyConfirmationClosesOnlyTheMatchingRecovery() {
        val policy = recoveringPolicy(firstEpoch, now = 1_700)

        assertFalse(policy.markPathHealthy(firstEpoch.copy(stateRevision = 2L)))
        assertTrue(policy.markPathHealthy(firstEpoch))
        assertFalse(policy.isRecovering(firstEpoch))
    }

    @Test
    fun automaticStatusUsesOneValidatedAtomicPayload() {
        val status = AutoNodeSelectionStatus(
            profileId = 8L,
            phase = AutoNodeSelectionPhase.SWITCHED,
            latencyMs = 37,
            until = 9_000L,
        )

        assertEquals(status, AutoNodeSelectionStatus.decode(status.encode()))
        assertNull(AutoNodeSelectionStatus.decode(""))
        assertNull(AutoNodeSelectionStatus.decode("{not-json}"))
    }

    @Test
    fun logEvidenceMustBelongToCurrentNodeAndBeDefinite() {
        assertTrue(
            isDefiniteCurrentNodeFailure(
                "outbound/vless[node-12]: dial tcp: i/o timeout",
                "node-12",
            ),
        )
        assertFalse(
            isDefiniteCurrentNodeFailure(
                "outbound/vless[node-120]: dial tcp: i/o timeout",
                "node-12",
            ),
        )
        assertFalse(
            isDefiniteCurrentNodeFailure(
                "outbound/vless[node-12]: connection closed normally",
                "node-12",
            ),
        )
    }

    private fun recoveringPolicy(epoch: NodeFailureEpoch, now: Long): NodeFailoverPolicy =
        NodeFailoverPolicy().also { policy ->
            recordThreshold(policy, epoch, now - 1_600)
            assertTrue(policy.beginRecovery(epoch, now))
        }

    private fun recordThreshold(policy: NodeFailoverPolicy, epoch: NodeFailureEpoch, start: Long) {
        assertFalse(policy.recordRequestFailure(epoch, start, true))
        assertFalse(policy.recordRequestFailure(epoch, start + 800, true))
        assertTrue(policy.recordRequestFailure(epoch, start + 1_600, true))
    }
}
