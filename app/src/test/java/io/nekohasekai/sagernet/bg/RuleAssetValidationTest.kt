package io.nekohasekai.sagernet.bg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleAssetValidationTest {

    @Test
    fun validSrsAssetRequiresItsExactDigest() = withTemporaryDirectory { root ->
        val asset = File(root, "geoip-cn.srs").apply {
            writeBytes(srsBytes("trusted"))
        }
        val digest = sha256(asset.readBytes())

        assertTrue(RuleAssetsUpdater.isValidRuleAsset(asset))
        assertTrue(RuleAssetsUpdater.matchesRuleAssetDigest(asset, digest))
        assertFalse(RuleAssetsUpdater.matchesRuleAssetDigest(asset, "0".repeat(64)))
    }

    @Test
    fun rejectsInvalidSrsHeaderEvenWhenTheDigestMatches() = withTemporaryDirectory { root ->
        val asset = File(root, "geoip-cn.srs").apply {
            writeText("not an SRS file")
        }

        assertFalse(RuleAssetsUpdater.isValidRuleAsset(asset))
        assertFalse(RuleAssetsUpdater.matchesRuleAssetDigest(asset, sha256(asset.readBytes())))
    }

    @Test
    fun boundedCopyRefusesExpansionBeforeWritingBeyondLimit() {
        val output = ByteArrayOutputStream()

        assertThrows(IllegalArgumentException::class.java) {
            RuleAssetsUpdater.copyRuleAssetBounded(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                output = output,
                maxBytes = 4,
            )
        }

        assertArrayEquals(byteArrayOf(), output.toByteArray())
    }

    private fun srsBytes(payload: String) =
        byteArrayOf('S'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), 1) + payload.toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private inline fun withTemporaryDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("rule-asset-validation-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
