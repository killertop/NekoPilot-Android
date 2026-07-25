package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoNodeSelectorReloadGateTest {
    @Test
    fun manualSelectionIsRejectedWhileAFullReloadOwnsTheSelector() = runBlocking {
        val profile = ProxyEntity().apply {
            id = 7L
            type = ProxyEntity.TYPE_SOCKS
            configRevision = 1L
        }
        val selector = AutoNodeSelector(
            selectorTag = "proxy-test",
            profilesByTag = mapOf(AutoNodeSelector.nodeTag(profile.id) to profile),
            initialProfileId = profile.id,
            initiallyEnabled = false,
            initialNetworkIdentity = null,
            nextCandidate = { _, _ -> null },
            currentPathHealthy = { true },
            onSelected = {},
            onStatus = {},
            canSelect = { true },
        )

        try {
            selector.blockForReload()
            assertFalse(selector.selectManually(profile))
        } finally {
            selector.unblockAfterReload()
            selector.close()
        }
    }

    @Test
    fun frozenManualTargetIsReappliedAfterLastKnownGoodRebuild() = runBlocking {
        val firstProfile = testProfile(id = 7L)
        val secondProfile = testProfile(id = 8L)
        val selections = ArrayList<Pair<String, String>>()
        val selector = AutoNodeSelector(
            selectorTag = "proxy-test",
            profilesByTag = mapOf(
                AutoNodeSelector.nodeTag(firstProfile.id) to firstProfile,
                AutoNodeSelector.nodeTag(secondProfile.id) to secondProfile,
            ),
            initialProfileId = firstProfile.id,
            initiallyEnabled = false,
            initialNetworkIdentity = null,
            nextCandidate = { _, _ -> null },
            currentPathHealthy = { true },
            onSelected = {},
            onStatus = {},
            canSelect = { true },
            oneShotSelector = { selectorTag, nodeTag -> selections += selectorTag to nodeTag },
        )

        try {
            assertTrue(selector.selectManually(secondProfile))
            selector.blockForReload()
            val snapshot = requireNotNull(selector.captureReloadSnapshot())

            assertEquals("proxy-test", snapshot.selectorTag)
            assertEquals(AutoNodeSelector.nodeTag(secondProfile.id), snapshot.selectedNodeTag)
            assertTrue(selector.restoreReloadSnapshot(snapshot))
            assertEquals(
                listOf(
                    "proxy-test" to AutoNodeSelector.nodeTag(secondProfile.id),
                    "proxy-test" to AutoNodeSelector.nodeTag(secondProfile.id),
                ),
                selections,
            )
        } finally {
            selector.unblockAfterReload()
            selector.close()
        }
    }

    private fun testProfile(id: Long) = ProxyEntity().apply {
        this.id = id
        type = ProxyEntity.TYPE_SOCKS
        configRevision = 1L
    }
}
