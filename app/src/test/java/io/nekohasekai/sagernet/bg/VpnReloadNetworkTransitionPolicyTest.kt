package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnReloadNetworkTransitionPolicyTest {
    @Test
    fun stablePhysicalNetworkStillRequiresStrictHealthProof() {
        assertFalse(shouldKeepLastKnownGoodForNetworkTransition(101L, 101L))
    }

    @Test
    fun lossOrReplacementKeepsLastKnownGoodWithoutExhaustingHealthRetries() {
        assertTrue(shouldKeepLastKnownGoodForNetworkTransition(101L, null))
        assertTrue(shouldKeepLastKnownGoodForNetworkTransition(101L, 202L))
    }

    @Test
    fun absentInitialNetworkIsNeverPromotedAsAHealthyCandidate() {
        assertTrue(shouldKeepLastKnownGoodForNetworkTransition(null, 101L))
        assertTrue(shouldKeepLastKnownGoodForNetworkTransition(null, null))
    }
}
