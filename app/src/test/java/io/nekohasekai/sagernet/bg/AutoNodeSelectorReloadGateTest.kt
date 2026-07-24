package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
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
}
