package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleAssetSourceFallbackTest {

    @Test
    fun recognizesOnlyUpdaterTemporaryFiles() {
        assertEquals(true, RuleAssetsUpdater.isTemporaryFileName(
            ".geosite-cn.srs.8dab3dc5-094d-4247-9e45-e6029cef1030.download.tmp"
        ))
        assertEquals(true, RuleAssetsUpdater.isTemporaryFileName(
            ".geoip-cn.version.txt.8dab3dc5-094d-4247-9e45-e6029cef1030.tmp"
        ))
        assertEquals(false, RuleAssetsUpdater.isTemporaryFileName("geoip-cn.srs"))
        assertEquals(false, RuleAssetsUpdater.isTemporaryFileName(".other.download.tmp"))
        assertEquals(false, RuleAssetsUpdater.isTemporaryFileName(".geoip-cn.srs.backup"))
    }

    @Test
    fun primarySuccessDoesNotUseBackup() {
        val attempts = mutableListOf<String>()
        val fallbacks = mutableListOf<Pair<String, String>>()

        val result = firstSuccessfulSource(
            sources = listOf("primary", "backup"),
            onFallback = { failed, next, _ -> fallbacks += failed to next },
        ) { source ->
            attempts += source
            "success"
        }

        assertEquals("success", result)
        assertEquals(listOf("primary"), attempts)
        assertEquals(emptyList<Pair<String, String>>(), fallbacks)
    }

    @Test
    fun primaryFailureUsesBackup() {
        val attempts = mutableListOf<String>()
        val fallbacks = mutableListOf<Pair<String, String>>()

        val result = firstSuccessfulSource(
            sources = listOf("primary", "backup"),
            onFallback = { failed, next, _ -> fallbacks += failed to next },
        ) { source ->
            attempts += source
            if (source == "primary") error("primary unavailable")
            "backup-success"
        }

        assertEquals("backup-success", result)
        assertEquals(listOf("primary", "backup"), attempts)
        assertEquals(listOf("primary" to "backup"), fallbacks)
    }

    @Test
    fun finalFailureIsReturnedToCaller() {
        val primaryError = IllegalStateException("primary unavailable")
        val backupError = IllegalArgumentException("backup unavailable")

        val actual = assertThrows(IllegalArgumentException::class.java) {
            firstSuccessfulSource(
                sources = listOf("primary", "backup"),
                onFallback = { _, _, _ -> },
            ) { source ->
                throw if (source == "primary") primaryError else backupError
            }
        }

        assertSame(backupError, actual)
    }

    @Test
    fun cancellationDoesNotUseBackup() {
        val cancellation = CancellationException("cancelled")
        val attempts = mutableListOf<String>()
        var fallbackCalled = false

        val actual = assertThrows(CancellationException::class.java) {
            firstSuccessfulSource(
                sources = listOf("primary", "backup"),
                onFallback = { _, _, _ -> fallbackCalled = true },
            ) { source ->
                attempts += source
                throw cancellation
            }
        }

        assertSame(cancellation, actual)
        assertEquals(listOf("primary"), attempts)
        assertEquals(false, fallbackCalled)
    }
}
