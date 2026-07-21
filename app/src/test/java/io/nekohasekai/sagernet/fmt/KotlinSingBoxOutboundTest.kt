package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

class KotlinSingBoxOutboundTest {
    @Test
    fun buildsRealityVlessUsingOfficialSchema() {
        val bean = VMessBean().apply {
            serverAddress = "edge.example"
            serverPort = 443
            uuid = "11111111-1111-1111-1111-111111111111"
            alterId = -1
            encryption = "xtls-rprx-vision"
            security = "tls"
            sni = "cdn.example"
            realityPubKey = "public-key"
            realityShortId = "abcd"
        }

        val outbound = buildSingBoxOutbound(bean, "proxy")

        assertEquals("vless", outbound.getString("type"))
        assertEquals("xtls-rprx-vision", outbound.getString("flow"))
        assertEquals("cdn.example", outbound.getJSONObject("tls").getString("server_name"))
        assertEquals("public-key", outbound.getJSONObject("tls").getJSONObject("reality").getString("public_key"))
    }

    @Test
    fun buildsTrojanAnyTlsAndHysteria2WithoutPrivateCoreHelpers() {
        val trojan = buildSingBoxOutbound(TrojanBean().apply {
            serverAddress = "trojan.example"
            serverPort = 443
            password = "secret"
            security = "tls"
        }, "trojan")
        val anyTls = buildSingBoxOutbound(AnyTLSBean().apply {
            serverAddress = "anytls.example"
            serverPort = 443
            password = "secret"
            sni = "cdn.example"
        }, "anytls")
        val hysteria = buildSingBoxOutbound(HysteriaBean().apply {
            protocolVersion = 2
            serverAddress = "hy.example"
            serverPort = 443
            serverPorts = "443"
            authPayload = "secret"
        }, "hy2")

        assertEquals("trojan", trojan.getString("type"))
        assertEquals("anytls", anyTls.getString("type"))
        assertEquals("hysteria2", hysteria.getString("type"))
        assertTrue(hysteria.getJSONObject("tls").getJSONArray("alpn").toString().contains("h3"))
    }
}
