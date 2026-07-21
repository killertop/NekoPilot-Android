package io.nekohasekai.sagernet.fmt.trojan

import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseTrojan(server: String): TrojanBean {
    val link = server.replaceFirst("trojan://", "https://").toHttpUrlOrNull()
        ?: error("Invalid Trojan link")
    return TrojanBean().apply {
        parseDuckSoft(link)
        link.queryParameter("allowInsecure")?.let { allowInsecure = it == "1" || it == "true" }
        link.queryParameter("peer")?.takeIf(String::isNotBlank)?.let { sni = it }
        initializeDefaultValues()
    }
}
