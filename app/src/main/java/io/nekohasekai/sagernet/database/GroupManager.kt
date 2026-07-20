package io.nekohasekai.sagernet.database

import androidx.room.withTransaction
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.group.withSubscriptionUpdateLock
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateStarted(group: ProxyGroup, byUser: Boolean)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateBusy(group: ProxyGroup, message: String)
        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    @Volatile
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            try {
                what(listener)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Database state is authoritative. A stale Activity/Fragment listener must not
                // make a completed mutation look like a failed one or prevent other listeners.
                Logs.w("Group listener failed (${error.javaClass.simpleName})")
            }
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        withSubscriptionUpdateLock(groupId) {
            SagerDatabase.proxyDao.deleteAll(groupId)
            withContext(NonCancellable) {
                ProfileManager.reselectAfterRemoval(removedGroupIds = setOf(groupId))
            }
        }
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        val entities = SagerDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        SagerDatabase.proxyDao.updateProxy(entities)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        SagerDatabase.groupDao.updateGroup(group)
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    suspend fun deleteGroup(groupId: Long) {
        withSubscriptionUpdateLock(groupId) {
            SagerDatabase.instance.withTransaction {
                SagerDatabase.proxyDao.deleteByGroup(groupId)
                SagerDatabase.groupDao.deleteById(groupId)
            }
            withContext(NonCancellable) {
                ProfileManager.reselectAfterRemoval(removedGroupIds = setOf(groupId))
            }
        }
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        val groupIds = group.mapTo(linkedSetOf(), ProxyGroup::id)
        for (groupId in groupIds.sorted()) {
            withSubscriptionUpdateLock(groupId) {
                SagerDatabase.instance.withTransaction {
                    SagerDatabase.proxyDao.deleteByGroup(groupId)
                    SagerDatabase.groupDao.deleteById(groupId)
                }
                withContext(NonCancellable) {
                    ProfileManager.reselectAfterRemoval(removedGroupIds = groupIds)
                }
            }
        }
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

}
