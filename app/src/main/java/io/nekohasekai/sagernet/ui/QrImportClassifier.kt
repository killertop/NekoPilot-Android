package io.nekohasekai.sagernet.ui

import android.net.Uri

/** Returns the subscription deep link represented by a scanned QR payload, if any. */
internal fun scannedSubscriptionLink(text: String): String? {
    val candidate = text.trim()
    if (
        candidate.isEmpty() || candidate.length > MAX_SUBSCRIPTION_URL_UTF16_UNITS ||
        candidate.any { it == '\r' || it == '\n' }
    ) return null
    val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return when {
        scheme == "sn" && host == "subscription" -> candidate
        scheme == "clash" && host == "install-config" -> candidate
        scheme == "https" && host.isNotEmpty() ->
            "sn://subscription?url=" + Uri.encode(candidate)
        else -> null
    }
}
