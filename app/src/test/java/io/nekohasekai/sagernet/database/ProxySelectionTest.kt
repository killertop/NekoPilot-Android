package io.nekohasekai.sagernet.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySelectionTest {

    @Test
    fun unchangedMissingSelectionCanBeRecovered() {
        val original = ProxySelection(profileId = 9L, groupId = 2L)

        assertTrue(original.mayRecoverFrom(original, selectedProfileExists = false))
    }

    @Test
    fun validSelectionIsNeverReplacedByFallback() {
        val original = ProxySelection(profileId = 9L, groupId = 2L)

        assertFalse(original.mayRecoverFrom(original, selectedProfileExists = true))
    }

    @Test
    fun concurrentlySelectedNodeWinsOverSubscriptionFallback() {
        val original = ProxySelection(profileId = 0L, groupId = 2L)
        val userSelection = ProxySelection(profileId = 88L, groupId = 4L)

        assertFalse(userSelection.mayRecoverFrom(original, selectedProfileExists = true))
    }

    @Test
    fun concurrentVisibleGroupChangeIsNotOverwritten() {
        val original = ProxySelection(profileId = 0L, groupId = 2L)
        val navigated = ProxySelection(profileId = 0L, groupId = 5L)

        assertFalse(navigated.mayRecoverFrom(original, selectedProfileExists = false))
    }
}
