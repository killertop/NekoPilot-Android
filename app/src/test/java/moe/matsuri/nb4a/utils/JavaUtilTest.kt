package moe.matsuri.nb4a.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaUtilTest {
    @Test
    fun preservesJavaHelperBehavior() {
        assertTrue(JavaUtil.isNullOrBlank(null))
        assertTrue(JavaUtil.isNullOrBlank(" \n"))
        assertFalse(JavaUtil.isNotBlank("\t"))
        assertTrue(JavaUtil.isNotBlank("node"))
        assertTrue(JavaUtil.isEmpty(null))
        assertTrue(JavaUtil.isEmpty(byteArrayOf()))
        assertEquals("007fff80", JavaUtil.bytesToHex(byteArrayOf(0, 0x7f, -1, -128)))
    }
}
