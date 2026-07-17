package io.nekohasekai.sagernet.bg.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrafficUpdaterTest {

    @Test
    fun queriesEachTagOnceAndSharesDiffAcrossMatchingItems() {
        val firstProxy = TrafficUpdater.TrafficLooperData(tag = "proxy")
        val secondProxy = TrafficUpdater.TrafficLooperData(tag = "proxy")
        val bypass = TrafficUpdater.TrafficLooperData(tag = "bypass")
        var query = ""
        val updater = TrafficUpdater(
            queryStatsPacked = {
                query = it
                packedLongs(100L, 200L, 10L, 20L)
            },
            items = listOf(firstProxy, secondProxy, bypass),
        )

        updater.updateAll()

        assertEquals("proxy\nbypass", query)
        assertEquals(100L, firstProxy.tx)
        assertEquals(200L, firstProxy.rx)
        assertEquals(100L, secondProxy.tx)
        assertEquals(200L, secondProxy.rx)
        assertEquals(10L, bypass.tx)
        assertEquals(20L, bypass.rx)
    }

    @Test
    fun rejectsMalformedPackedStats() {
        val updater = TrafficUpdater(
            queryStatsPacked = { ByteArray(8) },
            items = listOf(TrafficUpdater.TrafficLooperData(tag = "proxy")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            updater.updateAll()
        }
    }

    private fun packedLongs(vararg values: Long): ByteArray =
        ByteBuffer.allocate(values.size * Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .apply { values.forEach(::putLong) }
            .array()
}
