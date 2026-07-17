package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupSafetyTest {
    @Test
    fun readsBoundedUtf8() {
        assertEquals("backup", BackupSafety.readUtf8(ByteArrayInputStream("backup".toByteArray()), 6))
    }

    @Test
    fun rejectsOversizedDocument() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupSafety.readUtf8(ByteArrayInputStream(ByteArray(9)), 8)
        }
    }

    @Test
    fun rejectsMalformedUtf8Document() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupSafety.readUtf8(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }

    @Test
    fun acceptsUrlSafeAndLegacyBase64() {
        BackupSafety.validateEncodedSection("profiles", listOf("YWJjLQ", "YWJj+Q=="))
    }

    @Test
    fun rejectsMalformedOrOversizedSections() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupSafety.validateEncodedSection("profiles", listOf("not base64!"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSafety.validateEncodedSection(
                "profiles", List(MAX_BACKUP_ITEMS_PER_SECTION + 1) { "YQ" }
            )
        }
    }

    @Test
    fun rejectsGroupsWithoutBasicImportTarget() {
        val subscription = ProxyGroup(
            id = 1L,
            userOrder = 1L,
            type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupSafety.validateDecodedData(emptyList(), listOf(subscription), null, null)
        }
    }

    @Test
    fun rejectsSettingsThatReferToMissingGroup() {
        val group = ProxyGroup(id = 1L, userOrder = 1L)
        val staleSelection = KeyValuePair(Key.PROFILE_GROUP).put(99L)
        assertThrows(IllegalArgumentException::class.java) {
            BackupSafety.validateDecodedData(emptyList(), listOf(group), null, listOf(staleSelection))
        }
    }

    @Test
    fun validatesPartialRestoreReferencesAgainstExistingData() {
        val staleRule = RuleEntity(id = 1L, userOrder = 1L, outbound = 99L)
        assertThrows(IllegalArgumentException::class.java) {
            BackupSafety.validateDecodedData(
                null,
                null,
                listOf(staleRule),
                null,
                existingProfileIds = setOf(1L),
            )
        }

        val staleSelection = KeyValuePair(Key.PROFILE_ID).put(99L)
        assertThrows(IllegalArgumentException::class.java) {
            BackupSafety.validateDecodedData(
                null,
                null,
                null,
                listOf(staleSelection),
                existingProfileIds = setOf(1L),
                existingGroupIds = setOf(1L),
            )
        }
    }

    @Test
    fun reconcilesSelectionsAfterProfileOnlyRestore() {
        val settings = listOf(
            KeyValuePair(Key.PROFILE_GROUP).put(9L),
            KeyValuePair(Key.PROFILE_ID).put(8L),
            KeyValuePair(Key.PROFILE_CURRENT).put(7L),
            KeyValuePair("untouched").put("value"),
        )

        val reconciled = BackupSafety.reconcileSelections(
            settings,
            profileIds = setOf(2L),
            groupIds = setOf(3L),
            fallbackGroupId = 3L,
        ).associateBy(KeyValuePair::key)

        assertEquals(3L, reconciled.getValue(Key.PROFILE_GROUP).long)
        assertEquals(0L, reconciled.getValue(Key.PROFILE_ID).long)
        assertEquals(0L, reconciled.getValue(Key.PROFILE_CURRENT).long)
        assertEquals("value", reconciled.getValue("untouched").string)
    }
}
