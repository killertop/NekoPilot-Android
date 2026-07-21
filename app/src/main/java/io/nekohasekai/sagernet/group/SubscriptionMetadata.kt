package io.nekohasekai.sagernet.group

import android.net.Uri

/** Bounded parsing of untrusted subscription response headers. */
internal object SubscriptionMetadata {
    private const val MAX_HEADER_LENGTH = 4_096
    private const val MAX_DISPLAY_NAME_CODE_POINTS = 80
    private val extendedFilename = Regex("(?i)(^|;)\\s*filename\\*\\s*=\\s*([^;]+)")
    private val regularFilename = Regex("(?i)(^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))")
    private val whitespace = Regex("[\\t\\n\\u000B\\u000C\\r ]+")
    data class UserInfo(
        val upload: Long?,
        val download: Long?,
        val total: Long?,
        val expire: Long?,
    )

    fun sanitizeUserInfo(headerValue: String): String =
        parseUserInfoValues(headerValue).entries
            .sortedBy { (key, _) -> listOf("upload", "download", "total", "expire").indexOf(key) }
            .joinToString("; ") { (key, value) -> "$key=$value" }

    fun parseUserInfo(headerValue: String): UserInfo {
        val values = parseUserInfoValues(headerValue)
        return UserInfo(
            upload = values["upload"],
            download = values["download"],
            total = values["total"],
            expire = values["expire"],
        )
    }

    fun displayName(contentDisposition: String): String? {
        if (contentDisposition.isBlank() || contentDisposition.length > MAX_HEADER_LENGTH) return null
        val extended = extendedFilename.find(contentDisposition)?.groupValues?.getOrNull(2)
        val regular = regularFilename.find(contentDisposition)?.let { match ->
            match.groupValues[2].ifEmpty { match.groupValues[3] }
        }
        val raw = extended?.let(::decodeExtendedFilename) ?: regular
        return raw?.let(::sanitizeDisplayName)?.takeIf(String::isNotBlank)
    }

    private fun parseUserInfoValues(headerValue: String): Map<String, Long> {
        if (headerValue.length > MAX_HEADER_LENGTH) return emptyMap()
        val values = linkedMapOf<String, Long>()
        headerValue.split(';').forEach { item ->
            val separator = item.indexOf('=')
            if (separator <= 0) return@forEach
            val key = item.substring(0, separator).trim().lowercase()
            val raw = item.substring(separator + 1).trim()
            if (key !in setOf("upload", "download", "total", "expire") ||
                key in values || raw.length !in 1..19
            ) return@forEach
            raw.toLongOrNull()?.takeIf { it >= 0L }?.let { values[key] = it }
        }
        return values
    }

    private fun decodeExtendedFilename(value: String): String? {
        val encoded = value.trim().trim('"')
        val delimiter = encoded.indexOf("''")
        if (delimiter < 0 || encoded.substring(delimiter + 2).isBlank()) return null
        val payload = encoded.substring(delimiter + 2)
        if (Regex("%(?![0-9A-Fa-f]{2})").containsMatchIn(payload)) return null
        return Uri.decode(payload)
    }

    private fun sanitizeDisplayName(value: String): String {
        val visible = value.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            .replace(whitespace, " ").trim()
        if (visible.isEmpty()) return ""
        return visible.codePoints().limit(MAX_DISPLAY_NAME_CODE_POINTS.toLong()).toArray()
            .joinToString(separator = "") { String(Character.toChars(it)) }
    }
}
