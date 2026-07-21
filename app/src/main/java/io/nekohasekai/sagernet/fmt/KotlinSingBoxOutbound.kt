package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin-owned configuration mapping for the protocols offered by the simplified product.
 * The output is the public sing-box JSON schema consumed unchanged by official libbox.
 */
internal fun buildSingBoxOutbound(bean: AbstractBean, tag: String): JSONObject = JSONObject().apply {
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
        is AnyTLSBean -> buildAnyTlsOutbound(bean)
        else -> error("Unsupported node type: ${bean.javaClass.simpleName}")
    }
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
    else -> null
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
