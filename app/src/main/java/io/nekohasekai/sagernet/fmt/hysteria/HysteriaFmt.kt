package io.nekohasekai.sagernet.fmt.hysteria

import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseHysteria(url: String): HysteriaBean {
    val link = url.replaceFirst("hysteria://", "https://").toHttpUrlOrNull()
        ?: error("Invalid Hysteria link")
    return HysteriaBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment.orEmpty()
        link.queryParameter("mport")?.let { serverAddress = serverAddress.wrapIPV6Host() + ":" + it }
        sni = link.queryParameter("peer").orEmpty()
        link.queryParameter("auth")?.takeIf(String::isNotBlank)?.let {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        allowInsecure = link.queryParameter("insecure") in setOf("1", "true")
        link.queryParameter("upmbps")?.toIntOrNull()?.let { uploadMbps = it }
        link.queryParameter("downmbps")?.toIntOrNull()?.let { downloadMbps = it }
        alpn = link.queryParameter("alpn").orEmpty()
        obfuscation = link.queryParameter("obfsParam").orEmpty()
        protocol = when (link.queryParameter("protocol")) {
            "faketcp" -> HysteriaBean.PROTOCOL_FAKETCP
            "wechat-video" -> HysteriaBean.PROTOCOL_WECHAT_VIDEO
            else -> HysteriaBean.PROTOCOL_UDP
        }
        initializeDefaultValues()
    }
}
