package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleAssetsUpdaterStorageTest {
    @Test
    fun runtimeSnapshotRepairsPrivateRuleDataAndIgnoresExternalFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        RuleAssetsUpdater.ensureBundledAssets(context)

        val privateDirectory = File(context.filesDir, "rule-assets")
        val privateGeoIp = File(privateDirectory, "geoip-cn.srs")
        val bundledBytes = privateGeoIp.readBytes()
        assertTrue(bundledBytes.size > 4)

        val externalGeoIp = context.getExternalFilesDir(null)?.let { directory ->
            File(directory, "geoip-cn.srs")
        }
        val previousExternalBytes = externalGeoIp?.takeIf(File::isFile)?.readBytes()
        try {
            privateGeoIp.writeBytes(bundledBytes.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 1).toByte()
            })
            externalGeoIp?.writeBytes(srsBytes("untrusted external rule data"))

            val snapshot = RuleAssetsUpdater.runtimeSnapshot(context)
            assertArrayEquals(bundledBytes, privateGeoIp.readBytes())
            assertArrayEquals(bundledBytes, File(snapshot.directory, "geoip-cn.srs").readBytes())
        } finally {
            RuleAssetsUpdater.ensureBundledAssets(context)
            externalGeoIp?.let { file ->
                if (previousExternalBytes == null) file.delete() else file.writeBytes(previousExternalBytes)
            }
        }
    }

    private fun srsBytes(payload: String) =
        byteArrayOf('S'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), 1) + payload.toByteArray()
}
