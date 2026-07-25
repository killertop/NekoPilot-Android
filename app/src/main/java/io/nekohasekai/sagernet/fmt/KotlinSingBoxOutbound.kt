package io.nekohasekai.sagernet.fmt

import android.util.Base64
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin-owned configuration mapping for the protocols offered by the simplified product.
 * The output is the public sing-box JSON schema consumed unchanged by official libbox.
 */
internal fun buildSingBoxOutbound(bean: AbstractBean, tag: String): JSONObject {
    unsupportedOfficialRuntimeProfileName(bean)?.let { name ->
        error("$name is not supported by the current official sing-box runtime; select a supported node or migrate this profile")
    }
    require(bean !is WireGuardBean) {
        "WireGuard must be emitted as a sing-box endpoint, not a legacy outbound"
    }
    return JSONObject().apply {
        put("tag", tag)
        put("server", bean.serverAddress)
        put("server_port", bean.serverPort)
        when (bean) {
            is VMessBean -> buildV2RayOutbound(bean)
            is TrojanBean -> buildTrojanOutbound(bean)
            is HttpBean -> buildHttpOutbound(bean)
            is SOCKSBean -> buildSocksOutbound(bean)
            is ShadowsocksBean -> buildShadowsocksOutbound(bean)
            is HysteriaBean -> buildHysteriaOutbound(bean)
            is TuicBean -> buildTuicOutbound(bean)
            is NaiveBean -> buildNaiveOutbound(bean)
            is ShadowTLSBean -> buildShadowTlsOutbound(bean)
            is SSHBean -> buildSshOutbound(bean)
            is AnyTLSBean -> buildAnyTlsOutbound(bean)
            else -> error("Unsupported node type: ${bean.javaClass.simpleName}")
        }
    }
}

/**
 * sing-box removed the legacy WireGuard outbound in 1.13. The endpoint still implements the
 * outbound interface, so it can be selected by a normal selector or used as a route target.
 */
internal fun buildSingBoxEndpoint(bean: AbstractBean, tag: String): JSONObject = when (bean) {
    is WireGuardBean -> buildWireGuardEndpoint(bean, tag)
    else -> error("${unsupportedOfficialRuntimeProfileName(bean) ?: bean.javaClass.simpleName} is not a supported sing-box endpoint")
}

/**
 * Legacy persisted models can still be present after an app upgrade, but this product deliberately
 * ships only the official sing-box runtime. Keep the unsupported set explicit so a connection
 * fails before configuration startup instead of degrading into an opaque `Unsupported node type`.
 */
internal fun unsupportedOfficialRuntimeProfileName(bean: AbstractBean): String? = when (bean) {
    is TrojanGoBean -> "Trojan-Go"
    is MieruBean -> "Mieru"
    is ChainBean -> "Chain"
    is NekoBean -> "Neko"
    is ConfigBean -> "Custom configuration"
    else -> null
}

private fun JSONObject.buildV2RayOutbound(bean: VMessBean) {
    put("type", if (bean.isVLESSProfile()) "vless" else "vmess")
    put("uuid", bean.uuid)
    if (bean.isVLESSProfile()) {
        bean.encryption.takeIf { it.isNotBlank() && it != "auto" }?.let { put("flow", it) }
    } else {
        put("alter_id", bean.alterId)
        put("security", bean.encryption.ifBlank { "auto" })
    }
    putV2RayTransportAndTls(bean)
}

private fun JSONObject.buildTrojanOutbound(bean: TrojanBean) {
    put("type", "trojan")
    put("password", bean.password)
    putV2RayTransportAndTls(bean)
}

private fun JSONObject.buildHttpOutbound(bean: HttpBean) {
    put("type", "http")
    bean.username.takeIf(String::isNotBlank)?.let { put("username", it) }
    bean.password.takeIf(String::isNotBlank)?.let { put("password", it) }
    if (bean.security == "tls") put("tls", buildTls(bean))
}

private fun JSONObject.buildSocksOutbound(bean: SOCKSBean) {
    put("type", "socks")
    put("version", when (bean.protocol) {
        SOCKSBean.PROTOCOL_SOCKS4 -> "4"
        SOCKSBean.PROTOCOL_SOCKS4A -> "4a"
        else -> "5"
    })
    bean.username.takeIf(String::isNotBlank)?.let { put("username", it) }
    bean.password.takeIf(String::isNotBlank)?.let { put("password", it) }
    if (bean.sUoT) put("udp_over_tcp", true)
}

private fun JSONObject.buildShadowsocksOutbound(bean: ShadowsocksBean) {
    put("type", "shadowsocks")
    put("method", bean.method)
    put("password", bean.password)
    bean.plugin.takeIf { it.isNotBlank() && !it.startsWith("none") }?.let { plugin ->
        val parts = plugin.split(';', limit = 2)
        put("plugin", parts.first())
        parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let { put("plugin_opts", it) }
    }
    if (bean.sUoT) put("udp_over_tcp", true)
}

private fun JSONObject.buildHysteriaOutbound(bean: HysteriaBean) {
    put("type", if (bean.protocolVersion == 1) "hysteria" else "hysteria2")
    val ports = bean.serverPorts.trim()
    ports.toIntOrNull()?.let { put("server_port", it) } ?: run {
        val ranges = ports.split(',').mapNotNull { value ->
            value.trim().replace('-', ':').takeIf(String::isNotBlank)
        }
        require(ranges.isNotEmpty()) { "Invalid Hysteria server ports" }
        put("server_ports", JSONArray(ranges))
    }
    put("hop_interval", "${bean.hopInterval.coerceAtLeast(1)}s")
    put("up_mbps", bean.uploadMbps.coerceAtLeast(0))
    put("down_mbps", bean.downloadMbps.coerceAtLeast(0))
    if (bean.protocolVersion == 1) {
        bean.obfuscation.takeIf(String::isNotBlank)?.let { put("obfs", it) }
        if (bean.disableMtuDiscovery) put("disable_mtu_discovery", true)
        when (bean.authPayloadType) {
            HysteriaBean.TYPE_BASE64 -> put("auth", bean.authPayload)
            HysteriaBean.TYPE_STRING -> put("auth_str", bean.authPayload)
        }
        bean.streamReceiveWindow.takeIf { it > 0 }?.let { put("recv_window_conn", it) }
        bean.connectionReceiveWindow.takeIf { it > 0 }?.let { put("recv_window", it) }
    } else {
        put("password", bean.authPayload)
        bean.obfuscation.takeIf(String::isNotBlank)?.let {
            put("obfs", JSONObject().put("type", "salamander").put("password", it))
        }
    }
    put("tls", buildTls(bean, forceH3 = bean.protocolVersion != 1))
}

private fun JSONObject.buildTuicOutbound(bean: TuicBean) {
    require(bean.protocolVersion != 4) { "TUIC v4 is not supported" }
    put("type", "tuic")
    put("uuid", bean.uuid)
    put("password", bean.token)
    put("congestion_control", bean.congestionController)
    if (bean.udpRelayMode == "quic") put("udp_relay_mode", "quic")
    if (bean.reduceRTT) put("zero_rtt_handshake", true)
    put("tls", JSONObject().apply {
        put("enabled", true)
        bean.sni.takeIf(String::isNotBlank)?.let { put("server_name", it) }
        bean.alpn.split(',', '\n', '\r').filter(String::isNotBlank).takeIf { it.isNotEmpty() }
            ?.let { put("alpn", JSONArray(it)) }
        if (bean.allowInsecure) put("insecure", true)
        if (bean.disableSNI) put("disable_sni", true)
        bean.caText.takeIf(String::isNotBlank)?.let { put("certificate", it) }
    })
}

private fun JSONObject.buildNaiveOutbound(bean: NaiveBean) {
    put("type", "naive")
    bean.username.takeIf(String::isNotBlank)?.let { put("username", it) }
    bean.password.takeIf(String::isNotBlank)?.let { put("password", it) }
    bean.insecureConcurrency.takeIf { it > 0 }?.let { put("insecure_concurrency", it) }
    parseNaiveExtraHeaders(bean.extraHeaders)?.let { put("extra_headers", it) }
    if (bean.sUoT) put("udp_over_tcp", true)
    if (bean.proto.equals("quic", ignoreCase = true)) put("quic", true)
    put("tls", buildNaiveTls(bean))
}

private fun JSONObject.buildShadowTlsOutbound(bean: ShadowTLSBean) {
    put("type", "shadowtls")
    put("version", bean.version.coerceIn(1, 3))
    if (bean.version >= 2) bean.password.takeIf(String::isNotBlank)?.let { put("password", it) }
    put("tls", buildTls(bean))
}

private fun JSONObject.buildSshOutbound(bean: SSHBean) {
    put("type", "ssh")
    bean.username.takeIf(String::isNotBlank)?.let { put("user", it) }
    when (bean.authType) {
        SSHBean.AUTH_TYPE_PRIVATE_KEY -> {
            bean.privateKey.takeIf(String::isNotBlank)?.let { put("private_key", it) }
            bean.privateKeyPassphrase.takeIf(String::isNotBlank)
                ?.let { put("private_key_passphrase", it) }
        }

        SSHBean.AUTH_TYPE_PASSWORD ->
            bean.password.takeIf(String::isNotBlank)?.let { put("password", it) }

        SSHBean.AUTH_TYPE_NONE -> Unit
        else -> error("Unsupported SSH authentication type: ${bean.authType}")
    }
    splitNonEmpty(bean.publicKey).takeIf { it.isNotEmpty() }?.let { put("host_key", JSONArray(it)) }
}

private fun buildWireGuardEndpoint(bean: WireGuardBean, tag: String): JSONObject {
    val addresses = splitNonEmpty(bean.localAddress)
    require(addresses.isNotEmpty()) { "WireGuard requires at least one local address" }
    require(bean.privateKey.isNotBlank()) { "WireGuard private key is required" }
    require(bean.peerPublicKey.isNotBlank()) { "WireGuard peer public key is required" }
    require(bean.serverAddress.isNotBlank() && bean.serverPort in 1..65_535) {
        "WireGuard peer address and port are required"
    }
    return JSONObject().apply {
        put("type", "wireguard")
        put("tag", tag)
        // The app already owns Android's VPN/TUN lifecycle. Keep WireGuard user-space only so a
        // selected peer cannot create a competing system interface.
        put("system", false)
        put("mtu", bean.mtu.coerceAtLeast(0))
        put("address", JSONArray(addresses))
        put("private_key", bean.privateKey)
        put("peers", JSONArray().put(JSONObject().apply {
            put("address", bean.serverAddress)
            put("port", bean.serverPort)
            put("public_key", bean.peerPublicKey)
            bean.peerPreSharedKey.takeIf(String::isNotBlank)?.let { put("pre_shared_key", it) }
            // The persisted WireGuard profile predates endpoint-specific allowed IP fields. Its
            // previous outbound semantics were a full tunnel, so retain that behavior until the
            // profile model exposes per-peer split-tunnel configuration.
            put("allowed_ips", JSONArray(listOf("0.0.0.0/0", "::/0")))
            bean.reserved.takeIf(String::isNotBlank)?.let { put("reserved", normalizeWireGuardReserved(it)) }
        }))
    }
}

private fun buildNaiveTls(bean: NaiveBean): JSONObject = JSONObject().apply {
    put("enabled", true)
    bean.sni.takeIf(String::isNotBlank)?.let { put("server_name", it) }
    bean.certificates.takeIf(String::isNotBlank)?.let { put("certificate", it) }
}

private fun parseNaiveExtraHeaders(raw: String): JSONObject? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    require(text.length <= MAX_EXTRA_HEADERS_CHARS) { "Naive extra headers are too large" }
    if (text.startsWith("{")) return JSONObject(text)
    return JSONObject().apply {
        text.split('\n', '\r').map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val separator = line.indexOf(':')
            require(separator > 0) { "Invalid Naive extra header" }
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(name.isNotEmpty() && value.isNotEmpty()) { "Invalid Naive extra header" }
            require(!has(name)) { "Duplicate Naive extra header: $name" }
            put(name, value)
        }
    }
}

private fun splitNonEmpty(value: String): List<String> = value
    .split(',', '\n', '\r')
    .map(String::trim)
    .filter(String::isNotEmpty)

/** Converts the UI's decimal triplet or legacy base64 value to the official byte-array shape. */
private fun normalizeWireGuardReserved(value: String): JSONArray {
    val parts = value.split(',', '\n', '\r', ' ', '\t')
        .map(String::trim)
        .filter(String::isNotEmpty)
    if (parts.size == 3) {
        val bytes = parts.map {
            it.toIntOrNull()?.takeIf { number -> number in 0..255 }
                ?: error("Invalid WireGuard reserved byte")
        }
        return JSONArray(bytes)
    }
    val decoded = runCatching { Base64.decode(value, Base64.DEFAULT) }
        .getOrElse { error("Invalid WireGuard reserved bytes") }
    require(decoded.size == 3) { "WireGuard reserved must contain exactly 3 bytes" }
    return JSONArray(decoded.map(Byte::toInt))
}

private const val MAX_EXTRA_HEADERS_CHARS = 64 * 1024

private fun JSONObject.buildAnyTlsOutbound(bean: AnyTLSBean) {
    put("type", "anytls")
    put("password", bean.password)
    put("tls", JSONObject().apply {
        put("enabled", true)
        bean.sni.takeIf(String::isNotBlank)?.let { put("server_name", it) }
        bean.alpn.split(',', '\n', '\r').filter(String::isNotBlank).takeIf { it.isNotEmpty() }
            ?.let { put("alpn", JSONArray(it)) }
        if (bean.allowInsecure) put("insecure", true)
        bean.certificates.takeIf(String::isNotBlank)?.let { put("certificate", it) }
        bean.utlsFingerprint.takeIf(String::isNotBlank)?.let {
            put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
        }
        bean.echConfig.takeIf(String::isNotBlank)?.let {
            put("ech", JSONObject().put("enabled", true).put("config", JSONArray(it.split(',', '\n', '\r'))))
        }
    })
}

private fun JSONObject.putV2RayTransportAndTls(bean: StandardV2RayBean) {
    when (bean.packetEncoding) {
        1 -> put("packet_encoding", "packetaddr")
        2 -> put("packet_encoding", "xudp")
    }
    buildTransport(bean)?.let { put("transport", it) }
    if (bean.security == "tls") put("tls", buildTls(bean))
}

private fun buildTransport(bean: StandardV2RayBean): JSONObject? = when (bean.type) {
    "", "tcp" -> null
    "ws" -> JSONObject().apply {
        put("type", "ws")
        val pathWithEarlyData = bean.path.ifBlank { "/" }
        val marker = pathWithEarlyData.lastIndexOf("?ed=")
        val path = if (marker >= 0) pathWithEarlyData.substring(0, marker) else pathWithEarlyData
        put("path", path)
        bean.host.takeIf(String::isNotBlank)?.let { put("headers", JSONObject().put("Host", it)) }
        val earlyData = bean.wsMaxEarlyData.takeIf { it > 0 }
            ?: pathWithEarlyData.substringAfter("?ed=", "").toIntOrNull()?.takeIf { it > 0 }
        earlyData?.let {
            put("max_early_data", it)
            put("early_data_header_name", bean.earlyDataHeaderName.ifBlank { "Sec-WebSocket-Protocol" })
        }
    }
    "http" -> JSONObject().apply {
        put("type", "http")
        put("path", bean.path.ifBlank { "/" })
        bean.host.split(',', '\n', '\r').filter(String::isNotBlank).takeIf { it.isNotEmpty() }
            ?.let { put("host", JSONArray(it)) }
        if (bean.security != "tls") put("method", "GET")
    }
    "grpc" -> JSONObject().put("type", "grpc").put("service_name", bean.path)
    "httpupgrade" -> JSONObject().put("type", "httpupgrade").put("host", bean.host).put("path", bean.path)
    else -> error("Unsupported V2Ray transport: ${bean.type}")
}

private fun buildTls(bean: StandardV2RayBean): JSONObject = JSONObject().apply {
    put("enabled", true)
    bean.sni.takeIf(String::isNotBlank)?.let { put("server_name", it) }
    bean.alpn.split(',', '\n', '\r').filter(String::isNotBlank).takeIf { it.isNotEmpty() }
        ?.let { put("alpn", JSONArray(it)) }
    bean.certificates.takeIf(String::isNotBlank)?.let { put("certificate", it) }
    if (bean.allowInsecure) put("insecure", true)
    bean.utlsFingerprint.takeIf(String::isNotBlank)?.let {
        put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
    }
    bean.realityPubKey.takeIf(String::isNotBlank)?.let { publicKey ->
        put("reality", JSONObject().put("enabled", true).put("public_key", publicKey).put("short_id", bean.realityShortId))
        if (!has("utls")) put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
    }
    if (bean.enableECH || bean.echConfig.isNotBlank()) {
        put("ech", JSONObject().put("enabled", true).apply {
            bean.echConfig.takeIf(String::isNotBlank)?.let { put("config", JSONArray(it.split(',', '\n', '\r'))) }
        })
    }
}

private fun buildTls(bean: HysteriaBean, forceH3: Boolean): JSONObject = JSONObject().apply {
    put("enabled", true)
    bean.sni.takeIf(String::isNotBlank)?.let { put("server_name", it) }
    val alpn = if (forceH3) listOf("h3") else bean.alpn.split(',', '\n', '\r').filter(String::isNotBlank)
    alpn.takeIf { it.isNotEmpty() }?.let { put("alpn", JSONArray(it)) }
    bean.caText.takeIf(String::isNotBlank)?.let { put("certificate", it) }
    if (bean.allowInsecure) put("insecure", true)
}
