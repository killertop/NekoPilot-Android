package io.nekohasekai.sagernet.database.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyValuePairTest {
    @Test
    fun stringSetUsesUtf8ByteLengths() {
        val value = KeyValuePair("set").put(setOf("中文", "emoji 😺"))
        assertEquals(setOf("中文", "emoji 😺"), value.stringSet)
    }

    @Test
    fun malformedStringSetDoesNotAllocateOrCrash() {
        val value = KeyValuePair("set").apply {
            valueType = KeyValuePair.TYPE_STRING_SET
            this.value = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        }
        assertNull(value.stringSet)
    }
}
