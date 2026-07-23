package io.nekohasekai.sagernet.database

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupChangeNotifierTest {
    @Test
    fun failingListenerCannotPreventRemainingObservers() = runBlocking {
        var delivered = 0
        val failing = listener { error("stale screen") }
        val healthy = listener { delivered++ }
        GroupChangeNotifier.addListener(failing)
        GroupChangeNotifier.addListener(healthy)

        try {
            GroupChangeNotifier.groupReloaded(7L)
        } finally {
            GroupChangeNotifier.removeListener(failing)
            GroupChangeNotifier.removeListener(healthy)
        }

        assertEquals(1, delivered)
    }

    private fun listener(onReload: suspend (Long) -> Unit) =
        object : GroupChangeNotifier.Listener {
            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupUpdated(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit
            override suspend fun groupUpdated(groupId: Long) = onReload(groupId)
        }
}
