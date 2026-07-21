package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BoundedIOTest {
    @Test
    fun acceptsInputAtLimit() {
        val bytes = "profile".toByteArray()
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readBytesLimited(bytes.size))
        assertEquals("profile", ByteArrayInputStream(bytes).readUtf8Limited(bytes.size))
    }

    @Test
    fun rejectsOversizedInputAndInvalidUtf8() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(9)).readBytesLimited(8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            byteArrayOf(0xC3.toByte(), 0x28).decodeUtf8Strict()
        }
    }

    @Test
    fun limitsCopiedBytes() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(9)).copyToLimited(output, 8)
        }
    }

    @Test
    fun rejectsImpossibleKryoAllocationBeforeAllocating() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3)).byteBuffer()
        assertThrows(IllegalArgumentException::class.java) {
            input.readBytes(Int.MAX_VALUE)
        }
    }
}
