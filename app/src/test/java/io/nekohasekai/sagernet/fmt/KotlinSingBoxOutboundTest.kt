package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean

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

    @Test
    fun buildsOfficialLibboxNaiveShadowTlsSshAndWireGuardEndpoint() {
        val naive = buildSingBoxOutbound(NaiveBean().apply {
            serverAddress = "naive.example"
            serverPort = 443
            username = "user"
            password = "secret"
            sni = "edge.example"
            extraHeaders = "User-Agent: NekoPilot\nX-Test: enabled"
            insecureConcurrency = 2
        }, "naive")
        assertEquals("naive", naive.getString("type"))
        assertEquals("edge.example", naive.getJSONObject("tls").getString("server_name"))
        assertEquals("NekoPilot", naive.getJSONObject("extra_headers").getString("User-Agent"))
        assertEquals(2, naive.getInt("insecure_concurrency"))

        val shadowTls = buildSingBoxOutbound(ShadowTLSBean().apply {
            serverAddress = "shadowtls.example"
            serverPort = 443
            version = 3
            password = "secret"
            sni = "edge.example"
        }, "shadowtls")
        assertEquals("shadowtls", shadowTls.getString("type"))
        assertEquals(3, shadowTls.getInt("version"))
        assertEquals("secret", shadowTls.getString("password"))

        val ssh = buildSingBoxOutbound(SSHBean().apply {
            serverAddress = "ssh.example"
            serverPort = 22
            username = "root"
            authType = SSHBean.AUTH_TYPE_PRIVATE_KEY
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----"
            publicKey = "ssh-ed25519 AAAA..."
        }, "ssh")
        assertEquals("ssh", ssh.getString("type"))
        assertEquals("root", ssh.getString("user"))
        assertEquals("ssh-ed25519 AAAA...", ssh.getJSONArray("host_key").getString(0))

        val wireGuard = buildSingBoxEndpoint(WireGuardBean().apply {
            serverAddress = "wg.example"
            serverPort = 51820
            localAddress = "10.0.0.2/32\nfd00::2/128"
            privateKey = "private-key"
            peerPublicKey = "peer-key"
            reserved = "1, 2, 3"
        }, "wireguard")
        assertEquals("wireguard", wireGuard.getString("type"))
        assertEquals(2, wireGuard.getJSONArray("address").length())
        val peer = wireGuard.getJSONArray("peers").getJSONObject(0)
        assertEquals("wg.example", peer.getString("address"))
        assertEquals(3, peer.getJSONArray("reserved").length())
        assertEquals(2, peer.getJSONArray("reserved").getInt(1))
        assertEquals("0.0.0.0/0", peer.getJSONArray("allowed_ips").getString(0))
    }

    @Test
    fun rejectsMalformedNaiveExtraHeaders() {
        val failure = runCatching {
            buildSingBoxOutbound(NaiveBean().apply {
                serverAddress = "naive.example"
                serverPort = 443
                extraHeaders = "not-a-header"
            }, "naive")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsUnsupportedV2RayTransportInsteadOfFallingBackToTcp() {
        val failure = runCatching {
            buildSingBoxOutbound(VMessBean().apply {
                serverAddress = "edge.example"
                serverPort = 443
                uuid = "11111111-1111-1111-1111-111111111111"
                type = "kcp"
            }, "vmess")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun rejectsWireGuardAsLegacyOutbound() {
        val failure = runCatching {
            buildSingBoxOutbound(WireGuardBean(), "wireguard")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun identifiesLegacyProfilesThatCannotRunOnOfficialLibbox() {
        assertEquals("Trojan-Go", unsupportedOfficialRuntimeProfileName(TrojanGoBean()))
        assertEquals("Mieru", unsupportedOfficialRuntimeProfileName(MieruBean()))
        assertEquals("Chain", unsupportedOfficialRuntimeProfileName(ChainBean()))
        assertEquals("Neko", unsupportedOfficialRuntimeProfileName(NekoBean()))
        assertEquals("Custom configuration", unsupportedOfficialRuntimeProfileName(ConfigBean()))
        assertEquals(null, unsupportedOfficialRuntimeProfileName(TrojanBean()))
    }
}
