package io.nekohasekai.sagernet.group

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object SubscriptionMetadata {
    private const val MAX_USER_INFO_LENGTH = 4 * 1024
    private const val MAX_CONTENT_DISPOSITION_LENGTH = 4 * 1024
    private const val MAX_DISPLAY_NAME_CODE_POINTS = 80
    private val supportedUserInfoKeys = listOf("upload", "download", "total", "expire")
    private val extendedFilename = Regex(
        pattern = "(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)",
        option = RegexOption.IGNORE_CASE,
    )
    private val regularFilename = Regex(
        pattern = "(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))",
        option = RegexOption.IGNORE_CASE,
    )

    data class UserInfo(
        val upload: Long?,
        val download: Long?,
        val total: Long?,
        val expire: Long?,
    )

    fun sanitizeUserInfo(headerValue: String): String {
        val values = parseUserInfoValues(headerValue)
        return supportedUserInfoKeys.mapNotNull { key ->
            values[key]?.let { value -> "$key=$value" }
        }.joinToString("; ")
    }

    fun parseUserInfo(headerValue: String): UserInfo {
        val values = parseUserInfoValues(headerValue)
        return UserInfo(
            upload = values["upload"],
            download = values["download"],
            total = values["total"],
            expire = values["expire"],
        )
    }

    private fun parseUserInfoValues(headerValue: String): Map<String, Long> {
        if (headerValue.length > MAX_USER_INFO_LENGTH) return emptyMap()
        val values = linkedMapOf<String, Long>()
        headerValue.split(';').forEach { item ->
            val separator = item.indexOf('=')
            if (separator <= 0) return@forEach

            val key = item.substring(0, separator).trim().lowercase()
            val value = item.substring(separator + 1).trim()
            if (key !in supportedUserInfoKeys || key in values || value.length !in 1..19) {
                return@forEach
            }
            values[key] = value.toLongOrNull()?.takeIf { it >= 0 } ?: return@forEach
        }
        return values
    }

    fun displayName(contentDisposition: String): String? {
        if (contentDisposition.isBlank() ||
            contentDisposition.length > MAX_CONTENT_DISPOSITION_LENGTH
        ) {
            return null
        }

        val extended = extendedFilename.find(contentDisposition)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"')
            ?.substringAfter("''", missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)
            ?.let { encoded ->
                runCatching {
                    URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                }.getOrNull()
            }
        val regular = regularFilename.find(contentDisposition)?.let { match ->
            match.groupValues[1].ifBlank { match.groupValues[2] }
        }

        return sanitizeDisplayName(extended ?: regular.orEmpty())
    }

    private fun sanitizeDisplayName(value: String): String? {
        val cleaned = value
            .filterNot { character ->
                character.isISOControl() ||
                    Character.getType(character) == Character.FORMAT.toInt()
            }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return null

        val endIndex = cleaned.offsetByCodePoints(
            0,
            cleaned.codePointCount(0, cleaned.length).coerceAtMost(MAX_DISPLAY_NAME_CODE_POINTS),
        )
        return cleaned.substring(0, endIndex)
    }
}
