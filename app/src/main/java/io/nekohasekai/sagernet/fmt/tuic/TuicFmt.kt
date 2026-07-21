package io.nekohasekai.sagernet.fmt.tuic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseTuic(url: String): TuicBean {
    val link = url.replaceFirst("tuic://", "https://", ignoreCase = true).toHttpUrlOrNull()
        ?: error("Invalid TUIC link")
    return TuicBean().apply {
        serverAddress = link.host
        serverPort = link.port
        uuid = link.username
        token = link.password.orEmpty()
        name = link.fragment.orEmpty()
        sni = link.queryParameter("sni").orEmpty()
        alpn = link.queryParameter("alpn").orEmpty().replace(',', '\n')
        congestionController = link.queryParameter("congestion_control")
            ?: link.queryParameter("congestion-controller")
            ?: congestionController
        udpRelayMode = link.queryParameter("udp_relay_mode")
            ?: link.queryParameter("udp-relay-mode")
            ?: udpRelayMode
        allowInsecure = link.queryParameter("allow_insecure").toBooleanParameter() ||
            link.queryParameter("insecure").toBooleanParameter()
        disableSNI = link.queryParameter("disable_sni").toBooleanParameter()
        reduceRTT = link.queryParameter("reduce_rtt").toBooleanParameter() ||
            link.queryParameter("zero_rtt_handshake").toBooleanParameter()
        protocolVersion = 5
        initializeDefaultValues()
    }
}

private fun String?.toBooleanParameter(): Boolean = this == "1" || equals("true", ignoreCase = true)
