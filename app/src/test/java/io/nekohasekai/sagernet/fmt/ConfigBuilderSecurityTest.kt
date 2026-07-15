package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigBuilderSecurityTest {
    @Test
    fun authenticatedLanProxyCanBindAllInterfaces() {
        assertEquals("0.0.0.0", mixedInboundBind(false, true))
    }

    @Test
    fun testsNeverExposeInbound() {
        assertEquals(LOCALHOST, mixedInboundBind(true, true))
    }

}
