package io.nekohasekai.sagernet.fmt.hysteria

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaFmtTest {
    @Test
    fun parsesHysteria2Json() {
        val bean = JSONObject(
            """{
              "server":"[2001:db8::1]:8443,8444",
              "auth":"secret",
              "tls":{"sni":"example.com","insecure":true},
              "obfs":{"type":"salamander","salamander":{"password":"obfs-secret"}},
              "bandwidth":{"up":"20 mbps","down":"100 mbps"},
              "quic":{"initStreamReceiveWindow":8388608,"disablePathMTUDiscovery":true}
            }"""
        ).parseHysteriaJson()

        assertEquals(2, bean.protocolVersion)
        assertEquals("2001:db8::1", bean.serverAddress)
        assertEquals("8443,8444", bean.serverPorts)
        assertEquals("secret", bean.authPayload)
        assertEquals("example.com", bean.sni)
        assertEquals("obfs-secret", bean.obfuscation)
        assertEquals(20, bean.uploadMbps)
        assertEquals(100, bean.downloadMbps)
        assertTrue(bean.allowInsecure)
        assertTrue(bean.disableMtuDiscovery)
    }

    @Test
    fun parsesHysteria1JsonWithoutNullableDefaults() {
        val bean = JSONObject(
            """{"server":"example.com:443","up_mbps":10,"down_mbps":50,"auth_str":"secret"}"""
        ).parseHysteriaJson()

        assertEquals(1, bean.protocolVersion)
        assertEquals("example.com", bean.serverAddress)
        assertEquals("443", bean.serverPorts)
        assertEquals("secret", bean.authPayload)
        assertFalse(bean.allowInsecure)
    }

}
