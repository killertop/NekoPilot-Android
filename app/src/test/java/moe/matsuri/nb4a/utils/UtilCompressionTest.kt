package moe.matsuri.nb4a.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.zip.DataFormatException

class UtilCompressionTest {
    @Test
    fun roundTripsZlibData() {
        val input = ByteArray(100_000) { (it % 251).toByte() }
        assertArrayEquals(input, Util.zlibDecompress(Util.zlibCompress(input, 9)))
    }

    @Test
    fun rejectsOversizedAndTruncatedStreams() {
        val compressed = Util.zlibCompress(ByteArray(1024), 9)
        assertThrows(IllegalArgumentException::class.java) {
            Util.zlibDecompress(compressed, 100)
        }
        assertThrows(DataFormatException::class.java) {
            Util.zlibDecompress(compressed.copyOf(compressed.size - 1))
        }
    }
}
