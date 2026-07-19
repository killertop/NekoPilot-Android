package io.nekohasekai.sagernet

import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentSecurityTest {
    @Test
    fun defaultConnectionTestUsesTls() {
        assertTrue(CONNECTION_TEST_URL.startsWith("https://"))
    }

}
