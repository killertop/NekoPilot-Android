package io.nekohasekai.sagernet.core

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

/** Keeps Android I/O, Parcel and Room ownership in Kotlin while Rust makes pure data decisions. */
internal object RustDataCore {
    init {
        System.loadLibrary("nekodata_core")
    }

    @JvmStatic
    private external fun validateBackupNative(request: String): String

    @JvmStatic
    private external fun planSubscriptionUpdateNative(request: String): String

    data class SubscriptionIncoming(val name: String, val identity: String)

    data class SubscriptionExisting(
        val id: Long,
        val name: String,
        val userOrder: Long,
        val identity: String,
    )

    enum class SubscriptionActionKind { ADD, UPDATE, REORDER, UNCHANGED }

    data class SubscriptionAction(
        val incomingIndex: Int,
        val existingId: Long?,
        val action: SubscriptionActionKind,
        val userOrder: Long,
    )

    data class SubscriptionPlan(val actions: List<SubscriptionAction>, val deletionIds: List<Long>)

    fun validateEncodedSection(name: String, values: List<String>) {
        callBackup(
            JSONObject()
                .put("operation", "validate_encoded_section")
                .put("name", name)
                .put("values", JSONArray().apply { values.forEach(::put) })
        )
    }

    fun validateDecodedData(
        profiles: List<ProxyEntity>?,
        groups: List<ProxyGroup>?,
        rules: List<RuleEntity>?,
        settings: List<KeyValuePair>?,
        existingProfileIds: Set<Long>?,
        existingGroupIds: Set<Long>?,
    ) {
        val request = JSONObject().put("operation", "validate_decoded_data")
        profiles?.let { decodedProfiles ->
            request.put("profiles", JSONArray().apply {
                decodedProfiles.forEach { profile ->
                    profile.requireBean()
                    put(
                        JSONObject()
                            .put("id", profile.id)
                            .put("group_id", profile.groupId)
                            .put("user_order", profile.userOrder)
                    )
                }
            })
        }
        groups?.let { decodedGroups ->
            request.put("groups", JSONArray().apply {
                decodedGroups.forEach { group ->
                    put(
                        JSONObject()
                            .put("id", group.id)
                            .put("user_order", group.userOrder)
                            .put("group_type", group.type)
                            .put("order", group.order)
                            .put("subscription_present", group.subscription != null)
                            .put("front_proxy", group.frontProxy)
                            .put("landing_proxy", group.landingProxy)
                    )
                }
            })
        }
        rules?.let { decodedRules ->
            request.put("rules", JSONArray().apply {
                decodedRules.forEach { rule ->
                    put(
                        JSONObject()
                            .put("id", rule.id)
                            .put("user_order", rule.userOrder)
                            .put("outbound", rule.outbound)
                    )
                }
            })
        }
        settings?.let { decodedSettings ->
            request.put("settings", JSONArray().apply {
                decodedSettings.forEach { setting ->
                    put(
                        JSONObject()
                            .put("key", setting.key)
                            .put("value_type", setting.valueType)
                            .put("value_size", setting.value.size)
                            .put("string_set_valid", setting.stringSet != null)
                            .putOptionalLong("long_value", setting.safeLongValue())
                    )
                }
            })
        }
        existingProfileIds?.let { request.put("existing_profile_ids", it.toJsonArray()) }
        existingGroupIds?.let { request.put("existing_group_ids", it.toJsonArray()) }
        callBackup(request)
    }

    fun reconcileSelections(
        settings: List<KeyValuePair>,
        profileIds: Set<Long>,
        groupIds: Set<Long>,
        fallbackGroupId: Long,
    ): List<KeyValuePair> {
        val response = callBackup(
            JSONObject()
                .put("operation", "reconcile_selections")
                .put("settings", JSONArray().apply {
                    settings.forEach { setting ->
                        put(
                            JSONObject()
                                .put("key", setting.key)
                                .putOptionalLong("long_value", setting.safeLongValue())
                        )
                    }
                })
                .put("profile_ids", profileIds.toJsonArray())
                .put("group_ids", groupIds.toJsonArray())
                .put("fallback_group_id", fallbackGroupId)
        )
        val replacements = response.getJSONObject("data").getJSONArray("replacements")
        val byKey = buildMap {
            for (index in 0 until replacements.length()) {
                val replacement = replacements.getJSONObject(index)
                put(replacement.getString("key"), replacement.getLong("value"))
            }
        }
        return settings.map { setting ->
            byKey[setting.key]?.let { replacement -> KeyValuePair(setting.key).put(replacement) } ?: setting
        }
    }

    fun planSubscriptionUpdate(
        incoming: List<SubscriptionIncoming>,
        existing: List<SubscriptionExisting>,
    ): SubscriptionPlan {
        val response = callSubscription(
            JSONObject()
                .put("operation", "plan")
                .put("incoming", JSONArray().apply {
                    incoming.forEach { profile ->
                        put(
                            JSONObject()
                                .put("name", profile.name)
                                .put("identity", profile.identity)
                        )
                    }
                })
                .put("existing", JSONArray().apply {
                    existing.forEach { profile ->
                        put(
                            JSONObject()
                                .put("id", profile.id)
                                .put("name", profile.name)
                                .put("user_order", profile.userOrder)
                                .put("identity", profile.identity)
                        )
                    }
                })
        ).getJSONObject("data")
        val actions = response.getJSONArray("actions")
        return SubscriptionPlan(
            actions = List(actions.length()) { index ->
                val action = actions.getJSONObject(index)
                SubscriptionAction(
                    incomingIndex = action.getInt("incoming_index"),
                    existingId = action.takeIf { it.has("existing_id") }?.getLong("existing_id"),
                    action = SubscriptionActionKind.valueOf(action.getString("action").uppercase()),
                    userOrder = action.getLong("user_order"),
                )
            },
            deletionIds = response.getJSONArray("deletion_ids").toLongList(),
        )
    }

    fun requiresSubscriptionSelectionFallback(selectedPresent: Boolean): Boolean {
        return callSubscription(
            JSONObject()
                .put("operation", "selection_fallback")
                .put("selected_present", selectedPresent)
        ).getJSONObject("data").getBoolean("required")
    }

    private fun callBackup(request: JSONObject): JSONObject = responseOf(validateBackupNative(request.toString()))

    private fun callSubscription(request: JSONObject): JSONObject =
        responseOf(planSubscriptionUpdateNative(request.toString()))

    private fun responseOf(raw: String): JSONObject {
        val response = JSONObject(raw)
        require(response.optBoolean("ok")) {
            response.optString("error", "Rust data core rejected this request")
        }
        return response
    }

    private fun Set<Long>.toJsonArray(): JSONArray = JSONArray().apply { forEach(::put) }

    private fun List<Long>.toJsonArray(): JSONArray = JSONArray().apply { forEach(::put) }

    private fun JSONArray.toLongList(): List<Long> = List(length()) { getLong(it) }

    private fun JSONObject.putOptionalLong(name: String, value: Long?): JSONObject = apply {
        if (value != null) put(name, value)
    }

    private fun KeyValuePair.safeLongValue(): Long? = when (valueType) {
        @Suppress("DEPRECATION") KeyValuePair.TYPE_INT -> {
            if (value.size == Int.SIZE_BYTES) ByteBuffer.wrap(value).int.toLong() else null
        }
        KeyValuePair.TYPE_LONG -> {
            if (value.size == Long.SIZE_BYTES) ByteBuffer.wrap(value).long else null
        }
        else -> null
    }
}
