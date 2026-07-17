package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType

internal fun List<ProxyGroup>.basicGroupForImport(current: ProxyGroup): ProxyGroup? =
    firstOrNull { it.id == current.id && it.type == GroupType.BASIC }
        ?: firstOrNull { it.type == GroupType.BASIC }

internal fun List<ProxyGroup>.indexOfGroupOrFirst(groupId: Long): Int {
    if (isEmpty()) return -1
    return indexOfFirst { it.id == groupId }.takeIf { it >= 0 } ?: 0
}
