package io.nekohasekai.sagernet.fmt.hysteria

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

fun parseHysteria(url: String): HysteriaBean {
    val lowerUrl = url.lowercase(Locale.ROOT)
    val protocolVersion = when {
        lowerUrl.startsWith("hysteria://") -> 1
        lowerUrl.startsWith("hysteria2://") || lowerUrl.startsWith("hy2://") -> 2
        else -> error("Unsupported Hysteria scheme")
    }
    val endpoint = parseHysteriaEndpoint(url)
    val link = endpoint.link
    validateHysteriaParameters(link, protocolVersion)
    return HysteriaBean().apply {
        this.protocolVersion = protocolVersion
        serverAddress = link.host
        serverPort = endpoint.initialPort
        name = link.fragment.orEmpty()
        serverPorts = endpoint.serverPorts
        allowInsecure = parseHysteriaBooleanParameter(link.queryParameter("insecure"))
        if (protocolVersion == 1) {
            sni = link.queryParameter("peer").orEmpty()
            link.queryParameter("auth")?.takeIf(String::isNotBlank)?.let {
                authPayloadType = HysteriaBean.TYPE_STRING
                authPayload = it
            }
            link.queryParameter("upmbps")?.let { uploadMbps = parseHysteriaV1Bandwidth("upmbps", it) }
            link.queryParameter("downmbps")?.let { downloadMbps = parseHysteriaV1Bandwidth("downmbps", it) }
            alpn = link.queryParameter("alpn").orEmpty()
            val obfsMode = link.queryParameter("obfs").orEmpty().lowercase(Locale.ROOT)
            require(obfsMode.isEmpty() || obfsMode == "xplus") {
                "Unsupported Hysteria obfuscation mode: $obfsMode"
            }
            obfuscation = link.queryParameter("obfsParam").orEmpty()
            protocol = when (link.queryParameter("protocol")) {
                null, "", "udp" -> HysteriaBean.PROTOCOL_UDP
                "faketcp", "wechat-video" -> error(
                    "Hysteria FakeTCP and WeChat Video modes are not supported by the official sing-box runtime",
                )
                else -> error("Unsupported Hysteria transport")
            }
        } else {
            sni = link.queryParameter("sni").orEmpty()
            obfuscation = link.queryParameter("obfs-password").orEmpty()
            hysteria2ObfsType = link.queryParameter("obfs").orEmpty()
                .ifBlank { HysteriaBean.HYSTERIA2_OBFS_SALAMANDER }
                .lowercase(Locale.ROOT)
            require(hysteria2ObfsType in setOf(
                HysteriaBean.HYSTERIA2_OBFS_SALAMANDER,
                HysteriaBean.HYSTERIA2_OBFS_GECKO,
            )) { "Unsupported Hysteria2 obfuscation type: $hysteria2ObfsType" }
            if (link.queryParameter("obfs") != null) {
                require(obfuscation.isNotBlank()) { "Hysteria2 obfuscation password is required" }
            }
            authPayload = link.username.let { username ->
                link.password?.takeIf(String::isNotEmpty)?.let { "$username:$it" } ?: username
            }
        }
        initializeDefaultValues()
    }
}

/**
 * Shared validation for imported, manually edited, and persisted Hysteria profiles.
 *
 * The UI must reject the same unsupported/invalid combinations as the JSON compiler; otherwise
 * a profile appears to save successfully but only fails once the VPN starts.
 */
internal fun validateHysteriaProfile(bean: HysteriaBean) {
    require(bean.protocolVersion in 1..2) {
        "Unsupported Hysteria version: ${bean.protocolVersion}"
    }
    parseHysteriaServerPorts(bean.serverPorts)
    require(bean.hopInterval > 0) { "Hysteria hop interval must be positive" }
    if (bean.protocolVersion == 1) {
        require(bean.protocol == HysteriaBean.PROTOCOL_UDP) {
            "Hysteria FakeTCP and WeChat Video modes are not supported by the official sing-box runtime"
        }
        require(bean.uploadMbps > 0) { "Hysteria upload speed must be positive" }
        require(bean.downloadMbps > 0) { "Hysteria download speed must be positive" }
        require(bean.authPayloadType in setOf(
            HysteriaBean.TYPE_NONE,
            HysteriaBean.TYPE_STRING,
            HysteriaBean.TYPE_BASE64,
        )) { "Unsupported Hysteria authentication type: ${bean.authPayloadType}" }
    } else {
        require(bean.uploadMbps >= 0) { "Hysteria upload speed must not be negative" }
        require(bean.downloadMbps >= 0) { "Hysteria download speed must not be negative" }
        if (bean.obfuscation.isNotBlank()) {
            val obfsType = bean.hysteria2ObfsType.lowercase(Locale.ROOT)
            require(obfsType in setOf(
                HysteriaBean.HYSTERIA2_OBFS_SALAMANDER,
                HysteriaBean.HYSTERIA2_OBFS_GECKO,
            )) { "Unsupported Hysteria2 obfuscation type: ${bean.hysteria2ObfsType}" }
            if (obfsType == HysteriaBean.HYSTERIA2_OBFS_GECKO) {
                val minSize = bean.hysteria2GeckoMinPacketSize
                val maxSize = bean.hysteria2GeckoMaxPacketSize
                require(minSize >= 0 && maxSize >= 0) {
                    "Hysteria2 Gecko packet sizes must not be negative"
                }
                require(minSize == 0 || maxSize == 0 || minSize <= maxSize) {
                    "Invalid Hysteria2 Gecko packet size range"
                }
            }
        }
    }
}

private data class ParsedHysteriaEndpoint(
    val link: okhttp3.HttpUrl,
    val serverPorts: String,
    val initialPort: Int,
)

/**
 * Hysteria2 standard URIs permit port hopping directly in the authority, e.g.
 * `hy2://password@example.com:123,5000-6000/`. OkHttp's URL parser correctly handles all
 * escaping and query fields but intentionally rejects that non-RFC port syntax, so isolate and
 * validate the port expression first, then parse an equivalent single-port URL for the rest.
 */
private fun parseHysteriaEndpoint(url: String): ParsedHysteriaEndpoint {
    val schemeEnd = url.indexOf("://")
    require(schemeEnd > 0) { "Invalid Hysteria link" }
    val authorityStart = schemeEnd + 3
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let {
        if (it < 0) url.length else it
    }
    val authority = url.substring(authorityStart, authorityEnd)
    require(authority.isNotBlank()) { "Invalid Hysteria link" }
    val hostPortStart = authority.lastIndexOf('@').let { if (it < 0) 0 else it + 1 }
    val hostPort = authority.substring(hostPortStart)
    val portStart = when {
        hostPort.startsWith('[') -> {
            val close = hostPort.indexOf(']')
            require(close > 1) { "Invalid Hysteria IPv6 host" }
            when {
                close == hostPort.lastIndex -> null
                hostPort.getOrNull(close + 1) == ':' -> close + 2
                else -> error("Invalid Hysteria host and port")
            }
        }

        ':' !in hostPort -> null
        else -> {
            val separator = hostPort.indexOf(':')
            require(separator > 0 && hostPort.indexOf(':', separator + 1) < 0) {
                "Invalid Hysteria host and port"
            }
            separator + 1
        }
    }
    val authorityPort = portStart?.let { start ->
        hostPort.substring(start).also { require(it.isNotBlank()) { "Invalid Hysteria port" } }
    }
    val parsedPort = authorityPort?.let(::parseHysteriaServerPorts)
    val replacementPort = parsedPort?.firstPort()
    val parseableUrl = if (authorityPort == null) url else url.replaceRange(
        authorityStart + hostPortStart + requireNotNull(portStart),
        authorityEnd,
        requireNotNull(replacementPort).toString(),
    )
    val link = parseableUrl.replaceFirst(Regex("^[a-zA-Z0-9]+://"), "https://").toHttpUrlOrNull()
        ?: error("Invalid Hysteria link")
    require(link.encodedPath == "/") { "Hysteria link must not contain a path" }

    val legacyPorts = link.queryParameter("mport")?.trim().orEmpty()
    val authorityUsesPortHopping = parsedPort is HysteriaServerPorts.Ranges
    require(!(authorityUsesPortHopping && legacyPorts.isNotBlank())) {
        "Hysteria link must not specify port hopping in both authority and mport"
    }
    val serverPorts = when {
        authorityUsesPortHopping -> requireNotNull(authorityPort)
        legacyPorts.isNotBlank() -> legacyPorts.also(::parseHysteriaServerPorts)
        authorityPort != null -> authorityPort
        else -> link.port.toString()
    }
    val initialPort = parseHysteriaServerPorts(serverPorts).firstPort()
    return ParsedHysteriaEndpoint(link, serverPorts, initialPort)
}

private fun HysteriaServerPorts.firstPort(): Int = when (this) {
    is HysteriaServerPorts.Single -> port
    is HysteriaServerPorts.Ranges -> values.first().substringBefore(':').toInt()
}

private fun validateHysteriaParameters(link: okhttp3.HttpUrl, protocolVersion: Int) {
    val supported = if (protocolVersion == 1) {
        HYSTERIA_V1_PARAMETERS
    } else {
        HYSTERIA_V2_PARAMETERS
    }
    require(link.queryParameterNames.all { it in supported }) { "Unsupported Hysteria link parameter" }
    link.queryParameterNames.forEach { parameter ->
        require(link.queryParameterValues(parameter).size == 1) {
            "Duplicate Hysteria link parameter: $parameter"
        }
    }
    if (protocolVersion == 2) {
        require(link.queryParameter("pinSHA256").isNullOrBlank()) {
            "Hysteria2 certificate pins cannot be represented safely by the official sing-box TLS schema"
        }
        require(link.queryParameter("ech").isNullOrBlank()) {
            "Hysteria2 ECH configuration is not supported by this profile format"
        }
    }
}

private fun parseHysteriaBooleanParameter(value: String?): Boolean = when (value?.lowercase()) {
    null, "", "0", "false" -> false
    "1", "true" -> true
    else -> error("Invalid Hysteria boolean parameter")
}

private fun parseHysteriaV1Bandwidth(name: String, value: String): Int = value.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: error("Hysteria $name must be positive")

private val HYSTERIA_V1_PARAMETERS = setOf(
    "auth",
    "peer",
    "upmbps",
    "downmbps",
    "alpn",
    "obfs",
    "obfsParam",
    "protocol",
    "insecure",
    "mport",
)

private val HYSTERIA_V2_PARAMETERS = setOf(
    "obfs",
    "obfs-password",
    "sni",
    "insecure",
    "pinSHA256",
    "ech",
    // Older compatible clients used this query parameter before the standard authority form.
    "mport",
)
