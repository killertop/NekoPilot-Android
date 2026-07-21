package io.nekohasekai.sagernet.group

import libcore.Libcore
import org.json.JSONObject

/** Thin Android adapter for subscription metadata decisions owned by the Go data core. */
internal object SubscriptionMetadata {
    data class UserInfo(
        val upload: Long?,
        val download: Long?,
        val total: Long?,
        val expire: Long?,
    )

    fun sanitizeUserInfo(headerValue: String): String =
        parseUserInfoResponse(headerValue).getString("sanitized")

    fun parseUserInfo(headerValue: String): UserInfo {
        val values = parseUserInfoResponse(headerValue)
        return UserInfo(
            upload = values.optionalLong("upload"),
            download = values.optionalLong("download"),
            total = values.optionalLong("total"),
            expire = values.optionalLong("expire"),
        )
    }

    fun displayName(contentDisposition: String): String? =
        Libcore.parseSubscriptionDisplayName(contentDisposition).takeIf(String::isNotBlank)

    private fun parseUserInfoResponse(headerValue: String): JSONObject =
        JSONObject(Libcore.parseSubscriptionUserInfo(headerValue))

    private fun JSONObject.optionalLong(name: String): Long? =
        takeIf { has(name) && !isNull(name) }?.getLong(name)
}
