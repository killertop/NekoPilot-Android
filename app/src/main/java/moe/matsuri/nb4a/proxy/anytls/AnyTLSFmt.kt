package moe.matsuri.nb4a.proxy.anytls

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseAnytls(url: String): AnyTLSBean {
    val link = url.replaceFirst("anytls://", "https://").toHttpUrlOrNull()
        ?: error("Invalid AnyTLS link")
    return AnyTLSBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment.orEmpty()
        password = link.username
        sni = link.queryParameter("sni") ?: ""
        allowInsecure = link.queryParameter("insecure") in setOf("1", "true")
        utlsFingerprint = link.queryParameter("fp") ?: ""
        initializeDefaultValues()
    }
}
