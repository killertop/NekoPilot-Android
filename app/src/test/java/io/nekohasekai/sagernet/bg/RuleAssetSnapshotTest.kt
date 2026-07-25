package io.nekohasekai.sagernet.bg

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleAssetSnapshotTest {

    @Test
    fun snapshotKeepsTheExactPairAfterMutableAssetsChange() = withTemporaryDirectory { root ->
        val source = File(root, "source").apply(::checkDirectory)
        val snapshots = File(root, "snapshots")
        writeRuleAsset(source, "geoip-cn.srs", "geoip-generation-one")
        writeRuleAsset(source, "geosite-cn.srs", "geosite-generation-one")

        val first = RuleAssetsUpdater.materializeRuntimeSnapshot(source, snapshots)
        val firstGeoip = File(first.directory, "geoip-cn.srs").readBytes()
        val firstGeosite = File(first.directory, "geosite-cn.srs").readBytes()

        writeRuleAsset(source, "geoip-cn.srs", "geoip-generation-two")
        writeRuleAsset(source, "geosite-cn.srs", "geosite-generation-two")
        val second = RuleAssetsUpdater.materializeRuntimeSnapshot(source, snapshots)

        assertNotEquals(first.fingerprint, second.fingerprint)
        assertArrayEquals(firstGeoip, File(first.directory, "geoip-cn.srs").readBytes())
        assertArrayEquals(firstGeosite, File(first.directory, "geosite-cn.srs").readBytes())
        assertTrue(String(File(second.directory, "geoip-cn.srs").readBytes()).endsWith("two"))
        assertTrue(String(File(second.directory, "geosite-cn.srs").readBytes()).endsWith("two"))
    }

    @Test
    fun identicalPairReusesItsContentAddressedSnapshot() = withTemporaryDirectory { root ->
        val source = File(root, "source").apply(::checkDirectory)
        val snapshots = File(root, "snapshots")
        writeRuleAsset(source, "geoip-cn.srs", "geoip")
        writeRuleAsset(source, "geosite-cn.srs", "geosite")

        val first = RuleAssetsUpdater.materializeRuntimeSnapshot(source, snapshots)
        val second = RuleAssetsUpdater.materializeRuntimeSnapshot(source, snapshots)

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.directory, second.directory)
    }

    @Test
    fun corruptSourceDoesNotPublishAUsableSnapshot() = withTemporaryDirectory { root ->
        val source = File(root, "source").apply(::checkDirectory)
        val snapshots = File(root, "snapshots")
        writeRuleAsset(source, "geoip-cn.srs", "geoip")
        File(source, "geosite-cn.srs").writeText("not an SRS file")

        assertThrows(IllegalArgumentException::class.java) {
            RuleAssetsUpdater.materializeRuntimeSnapshot(source, snapshots)
        }
        assertTrue(!snapshots.exists() || snapshots.listFiles().isNullOrEmpty())
    }

    private fun writeRuleAsset(directory: File, name: String, payload: String) {
        File(directory, name).writeBytes(
            byteArrayOf('S'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), 1) +
                payload.toByteArray(),
        )
    }

    private fun checkDirectory(directory: File): File {
        check(directory.mkdirs()) { "Unable to create ${directory.absolutePath}" }
        return directory
    }

    private inline fun withTemporaryDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("rule-asset-snapshot-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
