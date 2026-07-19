package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.core.RustDataCore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.ktx.readUtf8Limited
import java.io.InputStream

internal const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
internal const val MAX_BACKUP_ITEMS_PER_SECTION = 20_000
internal const val MAX_BACKUP_ITEM_CHARS = 8 * 1024 * 1024

internal object BackupSafety {
    fun readUtf8(input: InputStream, maxBytes: Int = MAX_BACKUP_BYTES): String {
        return input.readUtf8Limited(maxBytes, "Backup file")
    }

    fun validateEncodedSection(name: String, values: List<String>) {
        RustDataCore.validateEncodedSection(name, values)
    }

    fun validateDecodedData(
        profiles: List<ProxyEntity>?,
        groups: List<ProxyGroup>?,
        rules: List<RuleEntity>?,
        settings: List<KeyValuePair>?,
        existingProfileIds: Set<Long>? = null,
        existingGroupIds: Set<Long>? = null,
    ) {
        RustDataCore.validateDecodedData(
            profiles,
            groups,
            rules,
            settings,
            existingProfileIds,
            existingGroupIds,
        )
    }

    fun reconcileSelections(
        settings: List<KeyValuePair>,
        profileIds: Set<Long>,
        groupIds: Set<Long>,
        fallbackGroupId: Long,
    ): List<KeyValuePair> {
        return RustDataCore.reconcileSelections(settings, profileIds, groupIds, fallbackGroupId)
    }
}
