package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.ktx.readUtf8Limited
import java.io.InputStream

internal const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
internal const val MAX_BACKUP_ITEMS_PER_SECTION = 20_000
internal const val MAX_BACKUP_ITEM_CHARS = 8 * 1024 * 1024
private const val MAX_BACKUP_KEY_CHARS = 256

internal object BackupSafety {
    private val base64UrlPattern = Regex("^[A-Za-z0-9_\\-+/=]*$")

    fun readUtf8(input: InputStream, maxBytes: Int = MAX_BACKUP_BYTES): String {
        return input.readUtf8Limited(maxBytes, "Backup file")
    }

    fun validateEncodedSection(name: String, values: List<String>) {
        require(values.size <= MAX_BACKUP_ITEMS_PER_SECTION) { "$name contains too many items" }
        values.forEach { value ->
            require(value.length <= MAX_BACKUP_ITEM_CHARS) { "$name item is too large" }
            require(base64UrlPattern.matches(value)) { "$name contains invalid base64 data" }
        }
    }

    fun validateDecodedData(
        profiles: List<ProxyEntity>?,
        groups: List<ProxyGroup>?,
        rules: List<RuleEntity>?,
        settings: List<KeyValuePair>?,
    ) {
        require((profiles == null) == (groups == null)) { "Profiles and groups must be imported together" }
        val profileIds = profiles?.map { profile ->
            require(profile.id > 0 && profile.groupId > 0 && profile.userOrder >= 0) {
                "Profile contains invalid identifiers"
            }
            require(profile.tx >= 0 && profile.rx >= 0) { "Profile contains invalid traffic values" }
            profile.requireBean()
            profile.id
        }?.also { require(it.size == it.toSet().size) { "Profiles contain duplicate IDs" } }
            ?.toSet().orEmpty()

        groups?.let { decodedGroups ->
            require(decodedGroups.isEmpty() || decodedGroups.any { it.type == GroupType.BASIC }) {
                "Groups must contain a basic import target"
            }
            val groupIds = decodedGroups.map { group ->
                require(group.id > 0 && group.userOrder >= 0) { "Group contains invalid identifiers" }
                require(group.type == GroupType.BASIC || group.type == GroupType.SUBSCRIPTION) {
                    "Group contains an unsupported type"
                }
                require(group.order in GroupOrder.ORIGIN..GroupOrder.BY_DELAY) {
                    "Group contains an unsupported order"
                }
                require(group.type != GroupType.SUBSCRIPTION || group.subscription != null) {
                    "Subscription group is missing subscription data"
                }
                for (reference in listOf(group.frontProxy, group.landingProxy)) {
                    require(reference <= 0 || reference in profileIds) {
                        "Group refers to a missing profile"
                    }
                }
                group.id
            }
            require(groupIds.size == groupIds.toSet().size) { "Groups contain duplicate IDs" }
            val knownGroups = groupIds.toSet()
            require(profiles.orEmpty().all { it.groupId in knownGroups }) {
                "Profile refers to a missing group"
            }
        }

        rules?.let { decodedRules ->
            require(decodedRules.all { it.id > 0 && it.userOrder >= 0 }) {
                "Rule contains invalid identifiers"
            }
            require(decodedRules.map { it.id }.distinct().size == decodedRules.size) {
                "Rules contain duplicate IDs"
            }
            if (profiles != null) {
                require(decodedRules.all { it.outbound <= 0 || it.outbound in profileIds }) {
                    "Rule refers to a missing profile"
                }
            }
        }

        settings?.let { decodedSettings ->
            require(decodedSettings.map { it.key }.distinct().size == decodedSettings.size) {
                "Settings contain duplicate keys"
            }
            decodedSettings.forEach { setting ->
                require(setting.key.isNotBlank() && setting.key.length <= MAX_BACKUP_KEY_CHARS) {
                    "Setting contains an invalid key"
                }
                require(setting.value.size <= MAX_BACKUP_ITEM_CHARS) { "Setting value is too large" }
                val valid = when (setting.valueType) {
                    KeyValuePair.TYPE_BOOLEAN -> setting.value.size == 1
                    KeyValuePair.TYPE_FLOAT -> setting.value.size == Float.SIZE_BYTES
                    @Suppress("DEPRECATION") KeyValuePair.TYPE_INT -> setting.value.size == Int.SIZE_BYTES
                    KeyValuePair.TYPE_LONG -> setting.value.size == Long.SIZE_BYTES
                    KeyValuePair.TYPE_STRING -> true
                    KeyValuePair.TYPE_STRING_SET -> setting.stringSet != null
                    else -> false
                }
                require(valid) { "Setting contains an invalid value" }
            }
            if (groups != null) {
                val groupIds = groups.mapTo(HashSet(), ProxyGroup::id)
                decodedSettings.firstOrNull { it.key == Key.PROFILE_GROUP }?.long?.let { groupId ->
                    require(groupId <= 0L || groupId in groupIds) {
                        "Settings refer to a missing group"
                    }
                }
            }
            if (profiles != null) {
                for (key in listOf(Key.PROFILE_ID, Key.PROFILE_CURRENT)) {
                    decodedSettings.firstOrNull { it.key == key }?.long?.let { profileId ->
                        require(profileId <= 0L || profileId in profileIds) {
                            "Settings refer to a missing profile"
                        }
                    }
                }
            }
        }
    }
}
