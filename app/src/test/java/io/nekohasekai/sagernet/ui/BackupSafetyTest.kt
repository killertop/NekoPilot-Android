package io.nekohasekai.sagernet.ui

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
}
