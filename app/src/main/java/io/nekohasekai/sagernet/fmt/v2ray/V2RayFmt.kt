package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import okhttp3.HttpUrl

fun StandardV2RayBean.isTLS(): Boolean = security == "tls"

fun StandardV2RayBean.setTLS(enabled: Boolean) {
    security = if (enabled) "tls" else ""
}

/** Parses the shared Xray URI fields still used by the Trojan compatibility importer. */
fun StandardV2RayBean.parseDuckSoft(url: HttpUrl) {
    serverAddress = url.host
    serverPort = url.port
    name = url.fragment

    if (this is TrojanBean) {
        password = url.username
    } else {
        uuid = url.username
    }

    if (url.pathSegments.size > 1 || url.pathSegments[0].isNotBlank()) {
        path = url.pathSegments.joinToString("/")
    }

    type = url.queryParameter("type") ?: "tcp"
    if (type == "h2" || url.queryParameter("headerType") == "http") type = "http"

    security = url.queryParameter("security")
    if (security.isNullOrBlank()) {
        security = if (this is TrojanBean) "tls" else "none"
    }

    when (security) {
        "tls", "reality" -> {
            security = "tls"
            url.queryParameter("allowInsecure")?.let {
                allowInsecure = it == "1" || it == "true"
            }
            url.queryParameter("sni")?.let { sni = it }
            url.queryParameter("host")?.let {
                if (sni.isNullOrBlank()) sni = it
            }
            url.queryParameter("alpn")?.let { alpn = it }
            url.queryParameter("cert")?.let { certificates = it }
            url.queryParameter("pbk")?.let { realityPubKey = it }
            url.queryParameter("sid")?.let { realityShortId = it }
        }
    }

    when (type) {
        "http" -> {
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

        "httpupgrade" -> {
            url.queryParameter("host")?.let { host = it }
            url.queryParameter("path")?.let { path = it }
        }
    }

    if (this is VMessBean && !isVLESS) {
        url.queryParameter("encryption")?.let { encryption = it }
    }

    url.queryParameter("packetEncoding")?.let {
        when (it) {
            "packet" -> packetEncoding = 1
            "xudp" -> packetEncoding = 2
        }
    }

    url.queryParameter("flow")?.let {
        if (isVLESS) encryption = it.removeSuffix("-udp443")
    }
    url.queryParameter("fp")?.let { utlsFingerprint = it }
}
