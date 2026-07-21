package io.nekohasekai.sagernet.fmt.socks

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseSOCKS(link: String): SOCKSBean {
    val scheme = link.substringBefore("://").lowercase()
    require(scheme in setOf("socks", "socks4", "socks4a", "socks5")) { "Invalid SOCKS link" }
    val url = ("http://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Invalid SOCKS link")
    return SOCKSBean().apply {
        protocol = when (scheme) {
            "socks4" -> SOCKSBean.PROTOCOL_SOCKS4
            "socks4a" -> SOCKSBean.PROTOCOL_SOCKS4A
            else -> SOCKSBean.PROTOCOL_SOCKS5
        }
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        name = url.fragment.orEmpty()
        initializeDefaultValues()
    }
}
