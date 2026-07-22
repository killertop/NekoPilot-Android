package io.nekohasekai.sagernet

import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentSecurityTest {
    @Test
    fun defaultConnectionTestUsesCaptivePortalEndpoint() {
        assertEquals("http://cp.cloudflare.com/", DEFAULT_CONNECTION_TEST_URL)
    }

}
