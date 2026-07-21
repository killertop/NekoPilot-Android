package io.nekohasekai.sagernet.fmt.shadowsocks

import android.net.Uri
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseShadowsocks(link: String): ShadowsocksBean {
    require(link.startsWith("ss://", ignoreCase = true)) { "Invalid Shadowsocks link" }
    val raw = link.substringAfter("://")
    val fragment = link.substringAfter('#', missingDelimiterValue = "").let(Uri::decode)
    val authority = raw.substringBefore('#')
    val url = if ('@' in authority) {
        ("https://$authority").toHttpUrlOrNull() ?: error("Invalid Shadowsocks link")
    } else {
        val decoded = Util.b64Decode(authority).toString(Charsets.UTF_8)
        ("https://$decoded").toHttpUrlOrNull() ?: error("Invalid Shadowsocks link")
    }
    val credentials = if (url.password.isNotEmpty()) {
        url.username to url.password
    } else {
        val decoded = Util.b64Decode(url.username).toString(Charsets.UTF_8)
        val separator = decoded.indexOf(':')
        require(separator > 0) { "Invalid Shadowsocks credentials" }
        decoded.substring(0, separator) to decoded.substring(separator + 1)
    }
    return ShadowsocksBean().apply {
        serverAddress = url.host
        serverPort = url.port
        method = credentials.first
        password = credentials.second
        plugin = url.queryParameter("plugin").orEmpty().replaceFirst("simple-obfs", "obfs-local")
        name = url.fragment.orEmpty().ifBlank { fragment }
        initializeDefaultValues()
    }
}
