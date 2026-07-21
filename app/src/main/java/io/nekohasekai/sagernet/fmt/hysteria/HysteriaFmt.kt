package io.nekohasekai.sagernet.fmt.hysteria

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseHysteria(url: String): HysteriaBean {
    val lowerUrl = url.lowercase()
    val protocolVersion = when {
        lowerUrl.startsWith("hysteria://") -> 1
        lowerUrl.startsWith("hysteria2://") || lowerUrl.startsWith("hy2://") -> 2
        else -> error("Unsupported Hysteria scheme")
    }
    val link = url.replaceFirst(Regex("^[a-zA-Z0-9]+://"), "https://").toHttpUrlOrNull()
        ?: error("Invalid Hysteria link")
    return HysteriaBean().apply {
        this.protocolVersion = protocolVersion
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment.orEmpty()
        serverPorts = link.queryParameter("mport").orEmpty().ifBlank { link.port.toString() }
        allowInsecure = link.queryParameter("insecure") in setOf("1", "true")
        if (protocolVersion == 1) {
            sni = link.queryParameter("peer").orEmpty()
            link.queryParameter("auth")?.takeIf(String::isNotBlank)?.let {
                authPayloadType = HysteriaBean.TYPE_STRING
                authPayload = it
            }
            link.queryParameter("upmbps")?.toIntOrNull()?.takeIf { it >= 0 }?.let { uploadMbps = it }
            link.queryParameter("downmbps")?.toIntOrNull()?.takeIf { it >= 0 }?.let { downloadMbps = it }
            alpn = link.queryParameter("alpn").orEmpty()
            obfuscation = link.queryParameter("obfsParam").orEmpty()
            protocol = when (link.queryParameter("protocol")) {
                "faketcp" -> HysteriaBean.PROTOCOL_FAKETCP
                "wechat-video" -> HysteriaBean.PROTOCOL_WECHAT_VIDEO
                else -> HysteriaBean.PROTOCOL_UDP
            }
        } else {
            sni = link.queryParameter("sni").orEmpty()
            obfuscation = link.queryParameter("obfs-password").orEmpty()
            authPayload = link.username.let { username ->
                link.password?.takeIf(String::isNotEmpty)?.let { "$username:$it" } ?: username
            }
        }
        initializeDefaultValues()
    }
}
