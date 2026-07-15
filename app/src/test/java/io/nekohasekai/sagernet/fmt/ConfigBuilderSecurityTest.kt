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

    @Test
    fun routingPortsAreNormalizedAndInvalidValuesIgnored() {
        assertEquals(
            ParsedRulePorts(listOf(53, 443), listOf("1000:2000")),
            parseRulePorts("53, 443, 1000:2000, 0, 70000, invalid, 2000:1000")
        )
    }

    @Test
    fun routingPortsAreDeduplicated() {
        assertEquals(
            ParsedRulePorts(listOf(443), listOf("1000:2000")),
            parseRulePorts("443,443\n1000:2000, 1000:2000")
        )
    }
}
