package io.nekohasekai.sagernet.bg.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

class TrafficUpdater(
    private val box: libcore.BoxInstance,
    val items: List<TrafficLooperData>, // contain "bypass"
) {

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
        var ignore: Boolean = false,
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
        val updated = mutableMapOf<String, TrafficLooperData>() // diffs
        val tags = items.asSequence().filterNot { it.ignore }.map { it.tag }.distinct().toList()
        val packed = box.queryStatsPacked(tags.joinToString("\n"))
        require(packed.size == tags.size * 16) { "Invalid packed traffic response" }
        val buffer = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN)
        val stats = HashMap<String, Pair<Long, Long>>(tags.size)
        tags.forEach { stats[it] = buffer.long to buffer.long }
        items.forEach { item ->
            if (item.ignore) return@forEach
            var diff = updated[item.tag]
            // query a tag only once
            if (diff == null) {
                val stat = stats[item.tag]
                diff = updateOne(
                    item,
                    stat?.first ?: 0L,
                    stat?.second ?: 0L,
                )
                updated[item.tag] = diff
            } else {
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
            }
        }
//        Logs.d(JavaUtil.gson.toJson(items))
//        Logs.d(JavaUtil.gson.toJson(updated))
    }
}
