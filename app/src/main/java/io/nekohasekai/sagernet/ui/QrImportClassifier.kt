package io.nekohasekai.sagernet.ui

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Returns the subscription deep link represented by a scanned QR payload, if any. */
internal fun scannedSubscriptionLink(text: String): String? {
    val candidate = text.trim()
    if (candidate.isEmpty() || candidate.any { it == '\n' || it == '\r' }) return null

    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    return when {
        scheme == "sn" && uri.host.equals("subscription", ignoreCase = true) -> candidate
        scheme == "clash" && uri.host.equals("install-config", ignoreCase = true) -> candidate
        (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank() -> {
            val encoded = URLEncoder.encode(candidate, StandardCharsets.UTF_8.name())
            "sn://subscription?url=$encoded"
        }
        else -> null
    }
}
