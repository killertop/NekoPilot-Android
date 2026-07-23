package io.nekohasekai.sagernet.group

import androidx.room.withTransaction
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.SelectedProfileReloadCoordinator
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.core.ConnectionStateRepository
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.GroupChangeNotifier
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SelectionRepairAction
import io.nekohasekai.sagernet.database.rearrangeProfiles
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

object GroupManager {

    interface Listener : GroupChangeNotifier.Listener

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

    private val userInterfaceReference = AtomicReference<WeakReference<Interface>?>(null)

    val userInterface: Interface?
        get() = userInterfaceReference.get()?.get()

    fun registerUserInterface(userInterface: Interface) {
        userInterfaceReference.set(WeakReference(userInterface))
    }

    fun unregisterUserInterface(userInterface: Interface) {
        val registered = userInterfaceReference.get() ?: return
        if (registered.get() === userInterface) {
            userInterfaceReference.compareAndSet(registered, null)
        }
    }

    fun addListener(listener: Listener) {
        GroupChangeNotifier.addListener(listener)
    }

    fun removeListener(listener: Listener) {
        GroupChangeNotifier.removeListener(listener)
    }

    suspend fun clearGroup(groupId: Long) {
        withSubscriptionUpdateLock(groupId) {
            SagerDatabase.proxyDao.deleteAll(groupId)
            withContext(NonCancellable) {
                applySelectionRepair(
                    ProfileManager.reselectAfterRemoval(
                        removedGroupIds = setOf(groupId),
                        connectionStarted = ConnectionStateRepository.stateOrIdle.started,
                    ),
                )
            }
        }
        GroupChangeNotifier.groupReloaded(groupId)
    }

    fun rearrange(groupId: Long) {
        rearrangeProfiles(groupId)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        GroupChangeNotifier.groupChanged(group)
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        GroupChangeNotifier.groupReloaded(groupId)
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        GroupChangeNotifier.groupAdded(group)
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        SagerDatabase.groupDao.updateGroup(group)
        GroupChangeNotifier.groupChanged(group)
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
                applySelectionRepair(
                    ProfileManager.reselectAfterRemoval(
                        removedGroupIds = setOf(groupId),
                        connectionStarted = ConnectionStateRepository.stateOrIdle.started,
                    ),
                )
            }
        }
        GroupChangeNotifier.groupRemoved(groupId)
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
                    applySelectionRepair(
                        ProfileManager.reselectAfterRemoval(
                            removedGroupIds = groupIds,
                            connectionStarted = ConnectionStateRepository.stateOrIdle.started,
                        ),
                    )
                }
            }
        }
        for (proxyGroup in group) GroupChangeNotifier.groupRemoved(proxyGroup.id)
        SubscriptionUpdater.reconfigureUpdater()
    }

}

internal fun applySelectionRepair(action: SelectionRepairAction) = when (action) {
    SelectionRepairAction.None -> Unit
    SelectionRepairAction.StopService -> SagerNet.stopService()
    is SelectionRepairAction.ReloadSelectedProfile ->
        SelectedProfileReloadCoordinator.request(action.profileId, force = true)
}
