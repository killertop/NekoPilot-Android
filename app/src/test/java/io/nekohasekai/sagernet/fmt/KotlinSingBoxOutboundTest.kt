package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
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
    fun mapsHysteriaPortHoppingAndQuicFieldsToCurrentOfficialSchema() {
        val hysteria = buildSingBoxOutbound(HysteriaBean().apply {
            protocolVersion = 1
            serverAddress = "hy.example"
            serverPorts = "2000-2002, 3000"
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = "secret"
            uploadMbps = 10
            downloadMbps = 50
            streamReceiveWindow = 1024
            connectionReceiveWindow = 2048
            disableMtuDiscovery = true
            hopInterval = 15
        }, "hy1")

        assertEquals("hysteria", hysteria.getString("type"))
        assertEquals("2000:2002", hysteria.getJSONArray("server_ports").getString(0))
        assertEquals("3000:3000", hysteria.getJSONArray("server_ports").getString(1))
        assertTrue(!hysteria.has("server_port"))
        assertEquals("15s", hysteria.getString("hop_interval"))
        assertEquals(1024, hysteria.getInt("stream_receive_window"))
        assertEquals(2048, hysteria.getInt("connection_receive_window"))
        assertTrue(hysteria.getBoolean("disable_path_mtu_discovery"))
        assertEquals("secret", hysteria.getString("auth_str"))
        assertTrue(!hysteria.has("recv_window_conn"))
        assertTrue(!hysteria.has("disable_mtu_discovery"))
    }

    @Test
    fun rejectsMalformedOrLegacyOnlyHysteriaSettings() {
        val malformedPorts = runCatching {
            buildSingBoxOutbound(HysteriaBean().apply {
                protocolVersion = 2
                serverPorts = "443,not-a-port"
            }, "hy2")
        }.exceptionOrNull()
        assertTrue(malformedPorts is IllegalStateException)

        val legacyTransport = runCatching {
            buildSingBoxOutbound(HysteriaBean().apply {
                protocolVersion = 1
                protocol = HysteriaBean.PROTOCOL_FAKETCP
                serverPorts = "443"
            }, "hy1")
        }.exceptionOrNull()
        assertTrue(legacyTransport is IllegalArgumentException || legacyTransport is IllegalStateException)

        val invalidVersion = runCatching {
            buildSingBoxOutbound(HysteriaBean().apply {
                protocolVersion = 3
                serverPorts = "443"
            }, "hy")
        }.exceptionOrNull()
        assertTrue(invalidVersion is IllegalArgumentException || invalidVersion is IllegalStateException)

        val missingV1Bandwidth = runCatching {
            buildSingBoxOutbound(HysteriaBean().apply {
                protocolVersion = 1
                serverPorts = "443"
                uploadMbps = 0
                downloadMbps = 0
            }, "hy1")
        }.exceptionOrNull()
        assertTrue(missingV1Bandwidth is IllegalArgumentException)

        val h2Bbr = buildSingBoxOutbound(HysteriaBean().apply {
            protocolVersion = 2
            serverPorts = "443"
            uploadMbps = 0
            downloadMbps = 0
        }, "hy2")
        assertEquals(0, h2Bbr.getInt("up_mbps"))
        assertEquals(0, h2Bbr.getInt("down_mbps"))
    }

    @Test
    fun mapsHysteria2GeckoObfuscationAndRejectsInvalidPacketRanges() {
        val hysteria = buildSingBoxOutbound(HysteriaBean().apply {
            protocolVersion = 2
            serverAddress = "hy2.example"
            serverPorts = "443"
            authPayload = "password"
            obfuscation = "mask"
            hysteria2ObfsType = HysteriaBean.HYSTERIA2_OBFS_GECKO
            hysteria2GeckoMinPacketSize = 64
            hysteria2GeckoMaxPacketSize = 512
        }, "hy2")

        val obfs = hysteria.getJSONObject("obfs")
        assertEquals("gecko", obfs.getString("type"))
        assertEquals("mask", obfs.getString("password"))
        assertEquals(64, obfs.getInt("min_packet_size"))
        assertEquals(512, obfs.getInt("max_packet_size"))

        val minOnly = buildSingBoxOutbound(HysteriaBean().apply {
            protocolVersion = 2
            serverPorts = "443"
            authPayload = "password"
            obfuscation = "mask"
            hysteria2ObfsType = HysteriaBean.HYSTERIA2_OBFS_GECKO
            hysteria2GeckoMinPacketSize = 64
        }, "hy2-min-only")
        val minOnlyObfs = minOnly.getJSONObject("obfs")
        assertEquals(64, minOnlyObfs.getInt("min_packet_size"))
        assertTrue(!minOnlyObfs.has("max_packet_size"))

        val maxOnly = buildSingBoxOutbound(HysteriaBean().apply {
            protocolVersion = 2
            serverPorts = "443"
            authPayload = "password"
            obfuscation = "mask"
            hysteria2ObfsType = HysteriaBean.HYSTERIA2_OBFS_GECKO
            hysteria2GeckoMaxPacketSize = 512
        }, "hy2-max-only")
        val maxOnlyObfs = maxOnly.getJSONObject("obfs")
        assertTrue(!maxOnlyObfs.has("min_packet_size"))
        assertEquals(512, maxOnlyObfs.getInt("max_packet_size"))

        val invalid = runCatching {
            buildSingBoxOutbound(HysteriaBean().apply {
                protocolVersion = 2
                serverPorts = "443"
                authPayload = "password"
                obfuscation = "mask"
                hysteria2ObfsType = HysteriaBean.HYSTERIA2_OBFS_GECKO
                hysteria2GeckoMinPacketSize = 512
                hysteria2GeckoMaxPacketSize = 64
            }, "hy2")
        }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException || invalid is IllegalStateException)
    }

    @Test
    fun mapsOnlyValidTuicV5SettingsToOfficialSchema() {
        val tuic = buildSingBoxOutbound(TuicBean().apply {
            protocolVersion = 5
            serverAddress = "tuic.example"
            serverPort = 443
            uuid = "2DD61D93-75D8-4DA4-AC0E-6AECE7EAC365"
            token = "password"
            congestionController = "BBR"
            udpRelayMode = "QUIC"
        }, "tuic")

        assertEquals("tuic", tuic.getString("type"))
        assertEquals("2dd61d93-75d8-4da4-ac0e-6aece7eac365", tuic.getString("uuid"))
        assertEquals("bbr", tuic.getString("congestion_control"))
        assertEquals("quic", tuic.getString("udp_relay_mode"))

        listOf(
            TuicBean().apply { protocolVersion = 4; uuid = "2dd61d93-75d8-4da4-ac0e-6aece7eac365"; token = "password" },
            TuicBean().apply { uuid = "not-a-uuid"; token = "password" },
            TuicBean().apply { uuid = "2dd61d93-75d8-4da4-ac0e-6aece7eac365"; token = "password"; congestionController = "reno" },
            TuicBean().apply { uuid = "2dd61d93-75d8-4da4-ac0e-6aece7eac365"; token = "password"; udpRelayMode = "stream" },
        ).forEach { bean ->
            val failure = runCatching { buildSingBoxOutbound(bean, "tuic") }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
        }
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
        assertEquals(
            "Hysteria FakeTCP / WeChat Video",
            unsupportedOfficialRuntimeProfileName(HysteriaBean().apply {
                protocolVersion = 1
                protocol = HysteriaBean.PROTOCOL_FAKETCP
            }),
        )
        assertEquals("TUIC v4", unsupportedOfficialRuntimeProfileName(TuicBean().apply { protocolVersion = 4 }))
        assertEquals("Trojan-Go", unsupportedOfficialRuntimeProfileName(TrojanGoBean()))
        assertEquals("Mieru", unsupportedOfficialRuntimeProfileName(MieruBean()))
        assertEquals("Chain", unsupportedOfficialRuntimeProfileName(ChainBean()))
        assertEquals("Neko", unsupportedOfficialRuntimeProfileName(NekoBean()))
        assertEquals(null, unsupportedOfficialRuntimeProfileName(ConfigBean()))
        assertEquals("Custom configuration", unsupportedOfficialRuntimeProfileName(ConfigBean().apply { type = 1 }))
        assertEquals(null, unsupportedOfficialRuntimeProfileName(TrojanBean()))
        assertTrue(!isOfficialRuntimeSelectable(TuicBean().apply { protocolVersion = 4 }))
        assertTrue(isOfficialRuntimeSelectable(ConfigBean()))
    }
}
