package io.nekohasekai.sagernet.fmt.hysteria

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File


// hysteria://host:port?auth=123456&peer=sni.domain&insecure=1|0&upmbps=100&downmbps=100&alpn=hysteria&obfs=xplus&obfsParam=123456#remarks
fun parseHysteria1(url: String): HysteriaBean {
    val link = url.replace("hysteria://", "https://").toHttpUrlOrNull() ?: error(
        "invalid hysteria link $url"
    )
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = link.host
        serverPorts = link.port.toString()
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("peer")?.also {
            sni = it
        }
        link.queryParameter("auth")?.takeIf { it.isNotBlank() }?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("upmbps")?.also {
            uploadMbps = it.toIntOrNull() ?: uploadMbps
        }
        link.queryParameter("downmbps")?.also {
            downloadMbps = it.toIntOrNull() ?: downloadMbps
        }
        link.queryParameter("alpn")?.also {
            alpn = it
        }
        link.queryParameter("obfsParam")?.also {
            obfuscation = it
        }
        link.queryParameter("protocol")?.also {
            when (it) {
                "faketcp" -> {
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                }

                "wechat-video" -> {
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                }
            }
        }
    }
}

// hysteria2://[auth@]hostname[:port]/?[key=value]&[key=value]...
fun parseHysteria2(url: String): HysteriaBean {
    val link = url
        .replace("hysteria2://", "https://")
        .replace("hy2://", "https://")
        .toHttpUrlOrNull() ?: error("invalid hysteria link $url")
    return HysteriaBean().apply {
        protocolVersion = 2
        serverAddress = link.host
        serverPorts = link.port.toString()
        authPayload = if (link.password.isNotBlank()) {
            link.username + ":" + link.password
        } else {
            link.username
        }
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
//        link.queryParameter("upmbps")?.also {
//            uploadMbps = it.toIntOrNull() ?: uploadMbps
//        }
//        link.queryParameter("downmbps")?.also {
//            downloadMbps = it.toIntOrNull() ?: downloadMbps
//        }
        link.queryParameter("obfs-password")?.also {
            obfuscation = it
        }
//        link.queryParameter("pinSHA256")?.also {
//            // TODO your box do not support it
//        }
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

@Deprecated("Use parseHysteriaJson", ReplaceWith("parseHysteriaJson()"))
fun JSONObject.parseHysteria1Json(): HysteriaBean = parseHysteriaJson()

fun HysteriaBean.buildHysteria1Config(port: Int, cacheFile: (() -> File)?): String {
    if (protocolVersion != 1) {
        throw Exception("error version: $protocolVersion")
    }
    return JSONObject().apply {
        put("server", displayAddress())
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                put("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                put("protocol", "wechat-video")
            }
        }
        put("up_mbps", uploadMbps)
        put("down_mbps", downloadMbps)
        put(
            "socks5", JSONObject(
                mapOf(
                    "listen" to "$LOCALHOST:$port",
                )
            )
        )
        put("retry", 5)
        put("fast_open", true)
        put("lazy_start", true)
        put("obfs", obfuscation)
        when (authPayloadType) {
            HysteriaBean.TYPE_BASE64 -> put("auth", authPayload)
            HysteriaBean.TYPE_STRING -> put("auth_str", authPayload)
        }
        if (sni.isBlank() && finalAddress == LOCALHOST && !serverAddress.isIpAddress()) {
            sni = serverAddress
        }
        if (sni.isNotBlank()) {
            put("server_name", sni)
        }
        if (alpn.isNotBlank()) put("alpn", alpn)
        if (caText.isNotBlank() && cacheFile != null) {
            val caFile = cacheFile()
            caFile.writeText(caText)
            put("ca", caFile.absolutePath)
        }

        if (allowInsecure) put("insecure", true)
        if (streamReceiveWindow > 0) put("recv_window_conn", streamReceiveWindow)
        if (connectionReceiveWindow > 0) put("recv_window", connectionReceiveWindow)
        if (disableMtuDiscovery) put("disable_mtu_discovery", true)

        put("hop_interval", hopInterval)
    }.toStringPretty()
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

fun buildSingBoxOutboundHysteriaBean(bean: HysteriaBean): SingBoxOptions.SingBoxOption {
    return when (bean.protocolVersion) {
        1 -> SingBoxOptions.Outbound_HysteriaOptions().apply {
            type = "hysteria"
            server = bean.serverAddress
            val port = bean.serverPorts.toIntOrNull()
            if (port != null) {
                server_port = port
            } else {
                server_ports = hopPortsToSingboxList(bean.serverPorts)
            }
            hop_interval = "${bean.hopInterval}s"
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            obfs = bean.obfuscation
            disable_mtu_discovery = bean.disableMtuDiscovery
            when (bean.authPayloadType) {
                HysteriaBean.TYPE_BASE64 -> auth = bean.authPayload
                HysteriaBean.TYPE_STRING -> auth_str = bean.authPayload
            }
            if (bean.streamReceiveWindow > 0) {
                recv_window_conn = bean.streamReceiveWindow.toLong()
            }
            if (bean.connectionReceiveWindow > 0) {
                recv_window_conn = bean.connectionReceiveWindow.toLong()
            }
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                if (bean.alpn.isNotBlank()) {
                    alpn = bean.alpn.listByLineOrComma()
                }
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || DataStore.globalAllowInsecure
                enabled = true
            }
        }

        2 -> SingBoxOptions.Outbound_Hysteria2Options().apply {
            type = "hysteria2"
            server = bean.serverAddress
            val port = bean.serverPorts.toIntOrNull()
            if (port != null) {
                server_port = port
            } else {
                server_ports = hopPortsToSingboxList(bean.serverPorts)
            }
            hop_interval = "${bean.hopInterval}s"
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            if (bean.obfuscation.isNotBlank()) {
                obfs = SingBoxOptions.Hysteria2Obfs().apply {
                    type = "salamander"
                    password = bean.obfuscation
                }
            }
//            disable_mtu_discovery = bean.disableMtuDiscovery
            password = bean.authPayload
//            if (bean.streamReceiveWindow > 0) {
//                recv_window_conn = bean.streamReceiveWindow.toLong()
//            }
//            if (bean.connectionReceiveWindow > 0) {
//                recv_window_conn = bean.connectionReceiveWindow.toLong()
//            }
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                alpn = listOf("h3")
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || DataStore.globalAllowInsecure
                enabled = true
            }
        }

        else -> error("error_version $bean.protocolVersion")
    }
}

fun hopPortsToSingboxList(s: String): List<String> {
    return s.split(",").mapNotNull {
        val pRange = it.replace("-", ":")
        if (pRange.split(":").size == 2) {
            pRange
        } else {
            null
        }
    }
}
