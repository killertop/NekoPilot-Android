package io.nekohasekai.sagernet.fmt.hysteria

import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import org.json.JSONObject


// hysteria://host:port?auth=123456&peer=sni.domain&insecure=1|0&upmbps=100&downmbps=100&alpn=hysteria&obfs=xplus&obfsParam=123456#remarks
fun parseHysteria1(url: String): HysteriaBean {
    return parseHysteriaLink(url).also { require(it.protocolVersion == 1) }
}

// hysteria2://[auth@]hostname[:port]/?[key=value]&[key=value]...
fun parseHysteria2(url: String): HysteriaBean {
    return parseHysteriaLink(url).also { require(it.protocolVersion == 2) }
}

private fun parseHysteriaLink(url: String): HysteriaBean {
    val data = JSONObject(Libcore.parseHysteriaLink(url))
    return HysteriaBean().apply {
        protocolVersion = data.getInt("protocolVersion")
        serverAddress = data.getString("serverAddress")
        serverPorts = data.getString("serverPorts")
        name = data.optString("name")
        authPayloadType = data.optInt("authPayloadType", HysteriaBean.TYPE_NONE)
        authPayload = data.optString("authPayload")
        sni = data.optString("sni")
        allowInsecure = data.optBoolean("allowInsecure")
        if (data.has("uploadMbps")) uploadMbps = data.getInt("uploadMbps")
        if (data.has("downloadMbps")) downloadMbps = data.getInt("downloadMbps")
        alpn = data.optString("alpn")
        obfuscation = data.optString("obfuscation")
        protocol = data.optInt("protocol", HysteriaBean.PROTOCOL_UDP)
    }
}

fun HysteriaBean.toUri(): String {
    var un = ""
    var pw = ""
    if (protocolVersion == 2) {
        if (authPayload.contains(":")) {
            un = authPayload.substringBefore(":")
            pw = authPayload.substringAfter(":")
        } else {
            un = authPayload
        }
    }
    //
    val builder = linkBuilder()
        .host(serverAddress)
        .port(getFirstPort(serverPorts))
        .username(un)
        .password(pw)
    if (isMultiPort(displayAddress())) {
        builder.addQueryParameter("mport", serverPorts)
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (protocolVersion == 1) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("peer", sni)
        }
        if (authPayload.isNotBlank()) {
            builder.addQueryParameter("auth", authPayload)
        }
        builder.addQueryParameter("upmbps", "$uploadMbps")
        builder.addQueryParameter("downmbps", "$downloadMbps")
        if (alpn.isNotBlank()) {
            builder.addQueryParameter("alpn", alpn)
        }
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", "xplus")
            builder.addQueryParameter("obfsParam", obfuscation)
        }
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                builder.addQueryParameter("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                builder.addQueryParameter("protocol", "wechat-video")
            }
        }
    } else {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", "salamander")
            builder.addQueryParameter("obfs-password", obfuscation)
        }
    }
    return builder.toLink(if (protocolVersion == 2) "hy2" else "hysteria")
}

private fun parseHysteriaServer(value: String): Pair<String, String> {
    val server = value.trim()
    require(server.isNotEmpty()) { "Missing Hysteria server" }
    if (server.startsWith("[")) {
        val end = server.indexOf(']')
        require(end > 1) { "Invalid Hysteria IPv6 server" }
        val ports = server.substring(end + 1).removePrefix(":").ifBlank { "443" }
        return server.substring(1, end) to ports
    }
    val separator = server.lastIndexOf(':')
    return if (separator > 0 && server.indexOf(':') == separator) {
        server.substring(0, separator) to server.substring(separator + 1).ifBlank { "443" }
    } else {
        server to "443"
    }
}

private fun Any?.toMbps(): Int? {
    val value = when (this) {
        is Number -> toDouble()
        is String -> Regex("""[-+]?\d+(?:\.\d+)?""").find(trim())?.value?.toDoubleOrNull()
        else -> null
    } ?: return null
    return value.toInt().takeIf { it >= 0 }
}

fun JSONObject.parseHysteriaJson(): HysteriaBean {
    val isHysteria2 = has("tls") || has("bandwidth") || has("quic") ||
        optJSONObject("obfs") != null || optString("version").equals("2", true)
    val (host, ports) = parseHysteriaServer(optString("server"))
    return HysteriaBean().apply {
        protocolVersion = if (isHysteria2) 2 else 1
        serverAddress = host
        serverPorts = ports
        initializeDefaultValues()

        if (isHysteria2) {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = optString("auth").ifBlank { optString("password") }
            val tls = optJSONObject("tls")
            sni = tls?.optString("sni")?.takeIf { it.isNotBlank() }
                ?: tls?.optString("serverName")?.takeIf { it.isNotBlank() }
                ?: ""
            allowInsecure = tls?.optBoolean("insecure", false) ?: false
            caText = tls?.optString("ca")?.takeIf { it.isNotBlank() } ?: ""
            val obfs = optJSONObject("obfs")
            obfuscation = obfs?.optJSONObject("salamander")?.optString("password")
                ?.takeIf { it.isNotBlank() }
                ?: obfs?.optString("password")?.takeIf { it.isNotBlank() }
                ?: ""
            val bandwidth = optJSONObject("bandwidth")
            uploadMbps = bandwidth?.opt("up").toMbps() ?: 0
            downloadMbps = bandwidth?.opt("down").toMbps() ?: 0
            val quic = optJSONObject("quic")
            streamReceiveWindow = quic?.opt("initStreamReceiveWindow").toMbps() ?: 0
            connectionReceiveWindow = quic?.opt("initConnReceiveWindow").toMbps() ?: 0
            disableMtuDiscovery = quic?.optBoolean("disablePathMTUDiscovery", false) ?: false
            hopInterval = opt("hopInterval").toMbps() ?: hopInterval
            name = optString("name").takeIf { it.isNotBlank() }
            return@apply
        }

        uploadMbps = opt("up_mbps").toMbps() ?: opt("up").toMbps() ?: uploadMbps
        downloadMbps = opt("down_mbps").toMbps() ?: opt("down").toMbps() ?: downloadMbps
        obfuscation = getStr("obfs") ?: obfuscation
        getStr("auth")?.also {
            authPayloadType = HysteriaBean.TYPE_BASE64
            authPayload = it
        }
        getStr("auth_str")?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        getStr("protocol")?.also {
            when (it) {
                "faketcp" -> {
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                }

                "wechat-video" -> {
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                }
            }
        }
        sni = getStr("server_name") ?: sni
        alpn = getStr("alpn") ?: alpn
        allowInsecure = getBool("insecure") ?: allowInsecure

        streamReceiveWindow = getIntNya("recv_window_conn") ?: streamReceiveWindow
        connectionReceiveWindow = getIntNya("recv_window") ?: connectionReceiveWindow
        disableMtuDiscovery = getBool("disable_mtu_discovery") ?: disableMtuDiscovery
    }
}

fun isMultiPort(hyAddr: String): Boolean {
    if (!hyAddr.contains(":")) return false
    val p = hyAddr.substringAfterLast(":")
    if (p.contains("-") || p.contains(",")) return true
    return false
}

fun getFirstPort(portStr: String): Int {
    return portStr.substringBefore(":").substringBefore(",").toIntOrNull() ?: 443
}

fun HysteriaBean.canUseSingBox(): Boolean {
    if (protocol != HysteriaBean.PROTOCOL_UDP) return false
    return true
}
