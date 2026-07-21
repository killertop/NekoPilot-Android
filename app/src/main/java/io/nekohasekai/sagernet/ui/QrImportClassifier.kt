package io.nekohasekai.sagernet.ui

import libcore.Libcore

/** Returns the subscription deep link represented by a scanned QR payload, if any. */
internal fun scannedSubscriptionLink(text: String): String? {
    return Libcore.scannedSubscriptionLink(text).takeIf(String::isNotEmpty)
}
