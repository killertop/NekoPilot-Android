package io.nekohasekai.sagernet.fmt.http

import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseHttp(link: String): HttpBean {
    val url = link.toHttpUrlOrNull() ?: error("Invalid HTTP proxy link")
    require(url.encodedPath == "/") { "HTTP proxy link must not contain a path" }
    return HttpBean().apply {
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        sni = url.queryParameter("sni").orEmpty()
        name = url.fragment.orEmpty()
        setTLS(url.scheme == "https")
        initializeDefaultValues()
    }
}
