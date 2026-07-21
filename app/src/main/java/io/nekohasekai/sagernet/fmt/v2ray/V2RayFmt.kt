package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.isVLESSProfile
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun StandardV2RayBean.isTLS(): Boolean = security == "tls"

fun StandardV2RayBean.setTLS(enabled: Boolean) {
    security = if (enabled) "tls" else ""
}

/** Shared Xray URI field parser used by VLESS and Trojan. */
fun StandardV2RayBean.parseDuckSoft(url: HttpUrl) {
    serverAddress = url.host
    serverPort = url.port
    name = url.fragment.orEmpty()
    if (this is TrojanBean) password = url.username else uuid = url.username

    if (url.pathSegments.size > 1 || url.pathSegments[0].isNotBlank()) {
        path = url.pathSegments.joinToString("/")
    }
    type = url.queryParameter("type") ?: "tcp"
    if (type == "h2" || url.queryParameter("headerType") == "http") type = "http"
    security = url.queryParameter("security").orEmpty()
    if (security.isNullOrBlank()) security = if (this is TrojanBean) "tls" else "none"

    when (security) {
        "tls", "reality" -> {
            security = "tls"
            allowInsecure = url.queryParameter("allowInsecure") in setOf("1", "true")
            url.queryParameter("sni")?.let { sni = it }
            url.queryParameter("host")?.takeIf { sni.isBlank() }?.let { sni = it }
            url.queryParameter("alpn")?.let { alpn = it }
            url.queryParameter("cert")?.let { certificates = it }
            url.queryParameter("pbk")?.let { realityPubKey = it }
            url.queryParameter("sid")?.let { realityShortId = it }
        }
    }
    when (type) {
        "http", "httpupgrade" -> {
            url.queryParameter("host")?.let { host = it }
            url.queryParameter("path")?.let { path = it }
        }
        "ws" -> {
            url.queryParameter("host")?.let { host = it }
            url.queryParameter("path")?.let { path = it }
            url.queryParameter("ed")?.toIntOrNull()?.takeIf { it >= 0 }?.let { earlyData ->
                wsMaxEarlyData = earlyData
                url.queryParameter("eh")?.let { earlyDataHeaderName = it }
            }
        }
        "grpc" -> url.queryParameter("serviceName")?.let { path = it }
    }
    if (this is VMessBean && !isVLESSProfile()) url.queryParameter("encryption")?.let { encryption = it }
    url.queryParameter("packetEncoding")?.let {
        packetEncoding = when (it) { "packet" -> 1; "xudp" -> 2; else -> 0 }
    }
    url.queryParameter("flow")?.let { if (isVLESSProfile()) encryption = it.removeSuffix("-udp443") }
    url.queryParameter("fp")?.let { utlsFingerprint = it }
}

/** Parses the current VLESS URI form without passing node payloads through JNI. */
fun parseVless(server: String): VMessBean {
    val link = server.replaceFirst("vless://", "https://").toHttpUrlOrNull()
        ?: error("Invalid VLESS link")
    return VMessBean().apply {
        alterId = -1
        parseDuckSoft(link)
        initializeDefaultValues()
    }
}
