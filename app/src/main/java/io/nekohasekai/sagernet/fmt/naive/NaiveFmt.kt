package io.nekohasekai.sagernet.fmt.naive

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Parses the de-facto NaiveProxy share-link form used by compatible clients:
 * `naive+https://user:password@host:443?...` or `naive+quic://...`.
 *
 * Only fields that the official sing-box Naive outbound can represent are accepted. Rejecting an
 * unknown parameter is intentional: dropping it would create a saved profile whose behavior no
 * longer matches the shared link.
 */
fun parseNaive(link: String): NaiveBean {
    val match = NAIVE_SCHEME.matchAt(link, 0)
        ?: error("Invalid Naive link scheme")
    val protocol = match.groupValues[1].lowercase()
    val url = ("https://" + link.substring(match.range.last + 1)).toHttpUrlOrNull()
        ?: error("Invalid Naive link")
    require(url.encodedPath == "/") { "Naive link must not contain a path" }
    require(url.queryParameterNames.all { it in SUPPORTED_NAIVE_PARAMETERS }) { "Unsupported Naive parameter" }
    url.queryParameterNames.forEach { parameter ->
        require(url.queryParameterValues(parameter).size == 1) {
            "Duplicate Naive parameter: $parameter"
        }
    }

    val extraHeaders = url.queryParameter("extra-headers").orEmpty()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    require(extraHeaders.length <= MAX_NAIVE_EXTRA_HEADERS_CHARS) {
        "Naive extra headers are too large"
    }
    val insecureConcurrency = url.queryParameter("insecure-concurrency")?.let { value ->
        value.toIntOrNull()?.takeIf { it >= 0 }
            ?: error("Invalid Naive insecure-concurrency")
    } ?: 0

    return NaiveBean().apply {
        proto = protocol
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        sni = url.queryParameter("sni").orEmpty()
        certificates = url.queryParameter("cert").orEmpty()
        this.extraHeaders = extraHeaders
        this.insecureConcurrency = insecureConcurrency
        name = url.fragment.orEmpty()
        initializeDefaultValues()
    }
}

private val NAIVE_SCHEME = Regex("(?i)^naive\\+(https|quic)://")
private val SUPPORTED_NAIVE_PARAMETERS = setOf(
    "sni",
    "cert",
    "extra-headers",
    "insecure-concurrency",
)
private const val MAX_NAIVE_EXTRA_HEADERS_CHARS = 64 * 1024
