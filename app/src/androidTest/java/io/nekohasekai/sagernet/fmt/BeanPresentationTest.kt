package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeanPresentationTest {
    @Test
    fun presentsKotlinProfileBeans() {
        val socks = SOCKSBean().apply {
            initializeDefaultValues()
            protocol = SOCKSBean.PROTOCOL_SOCKS4A
        }
        assertEquals("SOCKS4A", socks.protocolNameForUi())

        val hysteria = HysteriaBean().apply {
            initializeDefaultValues()
            serverAddress = "2001:db8::1"
            serverPorts = "443,8443"
        }
        assertEquals("[2001:db8::1]:443,8443", hysteria.displayAddressForUi())

        val vmess = VMessBean().apply {
            initializeDefaultValues()
            alterId = 0
        }
        assertFalse(vmess.isVLESSProfile())
        vmess.alterId = -1
        assertTrue(vmess.isVLESSProfile())

        val config = ConfigBean().apply {
            initializeDefaultValues()
            type = 1
            this.config = """{"type":"direct"}"""
        }
        assertEquals("direct (sing-box)", config.displayTypeForUi())
    }
}
