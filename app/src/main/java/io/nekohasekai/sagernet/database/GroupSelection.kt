package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType

internal fun List<ProxyGroup>.basicGroupForImport(current: ProxyGroup): ProxyGroup? =
    firstOrNull { it.id == current.id && it.type == GroupType.BASIC }
        ?: firstOrNull { it.type == GroupType.BASIC }

internal fun List<ProxyGroup>.indexOfGroupOrFirst(groupId: Long): Int {
    if (isEmpty()) return -1
    return indexOfFirst { it.id == groupId }.takeIf { it >= 0 } ?: 0
}

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
