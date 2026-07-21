package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupSelectionTest {
    @Test
    fun fallsBackToExistingBasicGroupForImport() {
        val subscription = ProxyGroup(id = 2L, type = GroupType.SUBSCRIPTION)
        val basic = ProxyGroup(id = 1L, type = GroupType.BASIC)
        assertEquals(basic, listOf(subscription, basic).basicGroupForImport(subscription))
        assertNull(listOf(subscription).basicGroupForImport(subscription))
    }

    @Test
    fun keepsExistingPersistedGroup() {
        val groups = listOf(ProxyGroup(id = 10L), ProxyGroup(id = 20L))
        assertEquals(20L, groups.resolveGroupId(20L, 10L))
    }

    @Test
    fun recoversDeletedGroupFromSelectedProfile() {
        val groups = listOf(ProxyGroup(id = 10L), ProxyGroup(id = 20L))
        assertEquals(20L, groups.resolveGroupId(99L, 20L))
    }

    @Test
    fun fallsBackToFirstGroupWhenPersistedReferencesAreGone() {
        val groups = listOf(ProxyGroup(id = 10L), ProxyGroup(id = 20L))
        assertEquals(10L, groups.resolveGroupId(99L, 88L))
    }

    @Test
    fun updatesVisibleSubscriptionBeforeSelectedProfilesOldSubscription() {
        val visible = ProxyGroup(id = 10L, type = GroupType.SUBSCRIPTION)
        val selectedProfilesGroup = ProxyGroup(id = 20L, type = GroupType.SUBSCRIPTION)
        assertEquals(
            visible,
            listOf(selectedProfilesGroup, visible).subscriptionGroupForUpdate(10L, 20L),
        )
    }

    @Test
    fun resolvesSubscriptionUpdateFallbacksWithoutGuessingBetweenMultipleSources() {
        val basic = ProxyGroup(id = 1L, type = GroupType.BASIC)
        val first = ProxyGroup(id = 10L, type = GroupType.SUBSCRIPTION)
        val second = ProxyGroup(id = 20L, type = GroupType.SUBSCRIPTION)

        assertEquals(first, listOf(basic, first).subscriptionGroupForUpdate(1L, null))
        assertEquals(second, listOf(first, second).subscriptionGroupForUpdate(99L, 20L))
        assertNull(listOf(first, second).subscriptionGroupForUpdate(99L, 88L))
    }
}
