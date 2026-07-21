package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType

internal fun List<ProxyGroup>.basicGroupForImport(current: ProxyGroup): ProxyGroup? =
    firstOrNull { it.id == current.id && it.type == GroupType.BASIC }
        ?: firstOrNull { it.type == GroupType.BASIC }

/**
 * Keeps the user's visible group when it still exists. If that persisted group was removed,
 * prefer the group containing the selected node before falling back to the first tab.
 */
internal fun List<ProxyGroup>.resolveGroupId(
    persistedGroupId: Long,
    selectedProfileGroupId: Long?,
): Long {
    check(isNotEmpty())
    return firstOrNull { it.id == persistedGroupId }?.id
        ?: firstOrNull { it.id == selectedProfileGroupId }?.id
        ?: first().id
}

/**
 * Resolves the airport source targeted by the Home action. The source the user is currently
 * viewing wins even when its first refresh failed and therefore has no selected profile yet.
 */
internal fun List<ProxyGroup>.subscriptionGroupForUpdate(
    persistedGroupId: Long,
    selectedProfileGroupId: Long?,
): ProxyGroup? {
    val subscriptions = filter { it.type == GroupType.SUBSCRIPTION }
    return subscriptions.firstOrNull { it.id == persistedGroupId }
        ?: subscriptions.firstOrNull { it.id == selectedProfileGroupId }
        ?: subscriptions.singleOrNull()
}
