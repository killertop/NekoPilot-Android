package io.nekohasekai.sagernet.bg.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

class TrafficUpdater internal constructor(
    private val queryStatsPacked: (String) -> ByteArray,
    val items: List<TrafficLooperData>, // contain "bypass"
) {

    constructor(box: libcore.BoxInstance, items: List<TrafficLooperData>) : this(
        box::queryStatsPacked,
        items,
    )

    private val itemsByTag = linkedMapOf<String, MutableList<TrafficLooperData>>().apply {
        items.forEach { item -> getOrPut(item.tag, ::mutableListOf).add(item) }
    }
    private val statsQuery = itemsByTag.keys.joinToString("\n")

    class TrafficLooperData(
        // Don't associate proxyEntity
        var tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
    )

    private fun updateOne(item: TrafficLooperData, tx: Long, rx: Long): TrafficLooperData {
        // last update
        val now = System.currentTimeMillis()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        if (interval <= 0) return item.apply {
            rxRate = 0
            txRate = 0
        }

        // add diff
        item.rx += rx
        item.tx += tx
        item.rxRate = rx * 1000 / interval
        item.txRate = tx * 1000 / interval

        // return diff
        return TrafficLooperData(
            tag = item.tag,
            rx = rx,
            tx = tx,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    fun updateAll() {
        val packed = queryStatsPacked(statsQuery)
        require(packed.size == itemsByTag.size * 16) { "Invalid packed traffic response" }
        val buffer = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN)
        itemsByTag.values.forEach { tagItems ->
            val diff = updateOne(tagItems.first(), buffer.long, buffer.long)
            for (index in 1 until tagItems.size) {
                val item = tagItems[index]
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
            }
        }
    }
}
