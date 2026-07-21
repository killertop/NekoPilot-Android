package moe.matsuri.nb4a.utils

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
    }
}
