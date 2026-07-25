package io.nekohasekai.sagernet.fmt.tuic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import java.util.UUID

fun parseTuic(url: String): TuicBean {
    val link = url.replaceFirst("tuic://", "https://", ignoreCase = true).toHttpUrlOrNull()
        ?: error("Invalid TUIC link")
    validateTuicUriParameters(link)
    val congestionController = normalizeTuicCongestionController(
        link.tuicParameter("congestion_control", "congestion-controller") ?: "cubic",
    )
    val udpRelayMode = normalizeTuicUdpRelayMode(
        link.tuicParameter("udp_relay_mode", "udp-relay-mode") ?: "native",
    )
    val token = link.password.orEmpty()
    val uuid = normalizeTuicUuid(link.username)
    val allowInsecure = parseTuicBooleanParameter(
        link.tuicParameter("insecure", "allow_insecure"),
        "insecure",
    )
    val reduceRtt = parseTuicBooleanParameter(
        link.tuicParameter("zero_rtt_handshake", "reduce_rtt"),
        "zero_rtt_handshake",
    )
    val disableSni = parseTuicBooleanParameter(link.queryParameter("disable_sni"), "disable_sni")
    validateTuicProfile(
        protocolVersion = 5,
        uuid = uuid,
        token = token,
        congestionController = congestionController,
        udpRelayMode = udpRelayMode,
    )
    return TuicBean().apply {
        serverAddress = link.host
        serverPort = link.port
        this.uuid = uuid
        this.token = token
        name = link.fragment.orEmpty()
        sni = link.queryParameter("sni").orEmpty()
        alpn = link.queryParameter("alpn").orEmpty().replace(',', '\n')
        this.congestionController = congestionController
        this.udpRelayMode = udpRelayMode
        this.allowInsecure = allowInsecure
        disableSNI = disableSni
        reduceRTT = reduceRtt
        protocolVersion = 5
        initializeDefaultValues()
    }
}

/** Validates the exact TUIC v5 subset represented by the persisted model and official libbox. */
internal fun validateTuicProfile(
    protocolVersion: Int,
    uuid: String,
    token: String,
    congestionController: String,
    udpRelayMode: String,
) {
    require(protocolVersion == 5) { "Only TUIC v5 is supported by the official sing-box runtime" }
    normalizeTuicUuid(uuid)
    require(token.isNotBlank()) { "TUIC password is required" }
    normalizeTuicCongestionController(congestionController)
    normalizeTuicUdpRelayMode(udpRelayMode)
}

internal fun normalizeTuicUuid(value: String): String {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
        ?: error("Invalid TUIC UUID")
    require(parsed.toString().equals(value, ignoreCase = true)) { "Invalid TUIC UUID" }
    return parsed.toString()
}

internal fun normalizeTuicCongestionController(value: String): String {
    val normalized = value.ifBlank { "cubic" }.lowercase(Locale.ROOT)
    require(normalized in TUIC_CONGESTION_CONTROLLERS) {
        "Unsupported TUIC congestion controller: $value"
    }
    return normalized
}

internal fun normalizeTuicUdpRelayMode(value: String): String {
    val normalized = value.ifBlank { "native" }.lowercase(Locale.ROOT)
    require(normalized in TUIC_UDP_RELAY_MODES) { "Unsupported TUIC UDP relay mode: $value" }
    return normalized
}

private fun validateTuicUriParameters(link: okhttp3.HttpUrl) {
    require(link.queryParameterNames.all { it in TUIC_URI_PARAMETERS }) {
        "Unsupported or lossy TUIC link parameter"
    }
    link.queryParameterNames.forEach { parameter ->
        require(link.queryParameterValues(parameter).size == 1) {
            "Duplicate TUIC link parameter: $parameter"
        }
    }
    listOf(
        "congestion_control" to "congestion-controller",
        "udp_relay_mode" to "udp-relay-mode",
        "insecure" to "allow_insecure",
        "zero_rtt_handshake" to "reduce_rtt",
    ).forEach { (primary, legacy) ->
        require(!(link.queryParameter(primary) != null && link.queryParameter(legacy) != null)) {
            "Duplicate TUIC link parameter: $primary"
        }
    }
}

private fun okhttp3.HttpUrl.tuicParameter(primary: String, legacy: String): String? =
    queryParameter(primary) ?: queryParameter(legacy)

private fun parseTuicBooleanParameter(value: String?, name: String): Boolean = when (value?.lowercase(Locale.ROOT)) {
    null, "", "0", "false" -> false
    "1", "true" -> true
    else -> error("Invalid TUIC $name parameter")
}

private val TUIC_URI_PARAMETERS = setOf(
    "sni",
    "alpn",
    "congestion_control",
    "congestion-controller",
    "udp_relay_mode",
    "udp-relay-mode",
    "insecure",
    "allow_insecure",
    "disable_sni",
    "zero_rtt_handshake",
    "reduce_rtt",
)

private val TUIC_CONGESTION_CONTROLLERS = setOf("cubic", "new_reno", "bbr")
private val TUIC_UDP_RELAY_MODES = setOf("native", "quic")
