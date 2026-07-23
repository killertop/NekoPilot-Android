package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException

/**
 * Data-layer change signal. It carries committed database facts and has no Activity/UI reference.
 */
object GroupChangeNotifier {
    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)
        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    private val listeners = ArrayList<Listener>()

    fun addListener(listener: Listener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    suspend fun groupAdded(group: ProxyGroup) = dispatch { groupAdd(group) }
    suspend fun groupChanged(group: ProxyGroup) = dispatch { groupUpdated(group) }
    suspend fun groupRemoved(groupId: Long) = dispatch { groupRemoved(groupId) }
    suspend fun groupReloaded(groupId: Long) = dispatch { groupUpdated(groupId) }

    private suspend fun dispatch(notify: suspend Listener.() -> Unit) {
        synchronized(listeners) { listeners.toList() }.forEach { listener ->
            try {
                notify(listener)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Persistence is already committed; a stale observer cannot roll it back.
                try {
                    Logs.w("Group listener failed (${error.javaClass.simpleName})")
                } catch (_: Throwable) {
                    // Logging is best effort and must not alter a committed database mutation.
                }
            }
        }
    }
}
