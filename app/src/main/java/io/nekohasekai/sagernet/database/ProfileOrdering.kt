package io.nekohasekai.sagernet.database

internal fun profileOrderUpdates(ids: List<Long>): List<ProxyEntity.OrderUpdate> =
    ids.mapIndexed { index, id -> ProxyEntity.OrderUpdate(id, (index + 1).toLong()) }

fun rearrangeProfiles(groupId: Long) {
    val orders = profileOrderUpdates(SagerDatabase.proxyDao.getIdsByGroup(groupId))
    if (orders.isNotEmpty()) SagerDatabase.proxyDao.updateOrders(orders)
}
