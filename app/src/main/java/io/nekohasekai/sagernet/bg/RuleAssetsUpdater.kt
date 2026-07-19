package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.KEEP
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Keeps the China direct-rule databases current without coupling data refreshes to
 * app releases. The bundled assets remain the offline bootstrap and last-known-good fallback.
 */
object RuleAssetsUpdater {

    private const val WORK_NAME = "RuleAssetsUpdater"
    private const val UPDATE_INTERVAL_DAYS = 7L
    private const val FIRST_CHECK_DELAY_HOURS = 6L
    private const val MAX_RULE_ASSET_BYTES = 16 * 1024 * 1024
    private const val MAX_CHECKSUM_BYTES = 512
    private const val USER_AGENT = "NekoPilot-rule-updater"

    private val officialAssets = listOf(
        RuleAsset("geoip.db", "SagerNet/sing-geoip"),
        RuleAsset("geosite.db", "SagerNet/sing-geosite"),
    )

    enum class UpdateResult { UPDATED, UP_TO_DATE }

    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            KEEP,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, UPDATE_INTERVAL_DAYS, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInitialDelay(FIRST_CHECK_DELAY_HOURS, TimeUnit.HOURS)
                .build()
        )
    }

    suspend fun updateNow(context: Context): UpdateResult {
        val assetsDirectory = context.getExternalFilesDir(null) ?: context.filesDir
        check(assetsDirectory.exists() || assetsDirectory.mkdirs()) { "Unable to create rule asset directory" }

        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            setTimeout(60_000)
            // Prefer the active NekoPilot tunnel when it is available. The HTTP
            // client deliberately falls back to a direct connection when it is not.
            trySocks5(
                DataStore.mixedPort,
                DataStore.mixedProxyUsername,
                DataStore.mixedProxyPassword,
            )
        }
        val candidates = ArrayList<RuleAssetCandidate>()
        try {
            for (asset in officialAssets) {
                val target = File(assetsDirectory, asset.fileName)
                val version = File(assetsDirectory, asset.fileNameWithoutExtension + ".version.txt")
                val localVersion = version.takeIf(File::isFile)?.readText()?.trim().orEmpty()
                // A user-imported data file is explicitly opted out from background replacement.
                if (localVersion == "Custom") continue

                val release = fetchLatestRelease(client, asset)
                if (release.tag == localVersion && target.isFile) continue

                val temporary = File(
                    assetsDirectory,
                    ".${asset.fileName}.${UUID.randomUUID()}.download.tmp",
                )
                try {
                    val response = client.newRequest().apply {
                        setURL(release.downloadUrl)
                        setUserAgent(USER_AGENT)
                    }.execute()
                    val content = response.content
                    require(content.isNotEmpty() && content.size <= MAX_RULE_ASSET_BYTES) {
                        "${asset.fileName} has an invalid size"
                    }
                    val checksum = client.newRequest().apply {
                        setURL(release.checksumUrl)
                        setUserAgent(USER_AGENT)
                    }.execute().content
                    verifyChecksum(asset.fileName, content, checksum)
                    temporary.outputStream().use { it.write(content) }
                    Libcore.validateRuleAsset(asset.fileName, temporary.canonicalPath)
                    candidates += RuleAssetCandidate(target, version, temporary, release.tag)
                } catch (error: Throwable) {
                    temporary.delete()
                    throw error
                }
            }

            candidates.forEach(RuleAssetCandidate::install)
            return if (candidates.isEmpty()) UpdateResult.UP_TO_DATE else UpdateResult.UPDATED
        } finally {
            candidates.forEach { it.temporary.delete() }
            client.close()
        }
    }

    private fun fetchLatestRelease(client: libcore.HTTPClient, asset: RuleAsset): RuleRelease {
        val response = client.newRequest().apply {
            setURL("https://api.github.com/repos/${asset.repo}/releases/latest")
            setUserAgent(USER_AGENT)
        }.execute()
        val release = JSONObject(Util.getStringBox(response.contentString))
        val tag = release.optString("tag_name").trim()
        require(tag.isNotBlank() && tag.length <= 128) { "Invalid ${asset.fileName} release version" }
        val releaseAssets = release.getJSONArray("assets")
        var downloadUrl: String? = null
        var checksumUrl: String? = null
        for (index in 0 until releaseAssets.length()) {
            val releaseAsset = releaseAssets.optJSONObject(index) ?: continue
            val url = releaseAsset.optString("browser_download_url")
                .takeIf { it.startsWith("https://") }
                ?: continue
            when (releaseAsset.optString("name")) {
                asset.fileName -> downloadUrl = url
                "${asset.fileName}.sha256sum" -> checksumUrl = url
            }
        }
        return RuleRelease(
            tag,
            requireNotNull(downloadUrl) { "${asset.fileName} is missing from release $tag" },
            requireNotNull(checksumUrl) { "${asset.fileName} checksum is missing from release $tag" },
        )
    }

    private fun verifyChecksum(fileName: String, content: ByteArray, checksum: ByteArray) {
        require(checksum.isNotEmpty() && checksum.size <= MAX_CHECKSUM_BYTES) {
            "$fileName has an invalid checksum"
        }
        val expected = checksum.decodeToString().trim().split(Regex("\\s+"))
            .firstOrNull()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: error("$fileName has an invalid checksum")
        val actual = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(actual.equals(expected, ignoreCase = true)) {
            "$fileName checksum verification failed"
        }
    }

    class UpdateTask(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result = try {
            when (updateNow(applicationContext)) {
                UpdateResult.UPDATED -> Logs.i("Rule assets updated")
                UpdateResult.UP_TO_DATE -> Logs.d("Rule assets already current")
            }
            Result.success()
        } catch (error: Throwable) {
            Logs.w(error)
            Result.retry()
        }
    }

    private data class RuleAsset(
        val fileName: String,
        val repo: String,
    ) {
        val fileNameWithoutExtension: String
            get() = fileName.substringBeforeLast('.')
    }

    private data class RuleRelease(
        val tag: String,
        val downloadUrl: String,
        val checksumUrl: String,
    )

    private data class RuleAssetCandidate(
        val target: File,
        val version: File,
        val temporary: File,
        val releaseTag: String,
    ) {
        fun install() {
            check(temporary.renameTo(target)) { "Unable to install ${target.name}" }
            val versionTemporary = File(
                version.parentFile,
                ".${version.name}.${UUID.randomUUID()}.tmp",
            )
            try {
                versionTemporary.writeText(releaseTag)
                check(versionTemporary.renameTo(version)) { "Unable to store ${target.name} version" }
            } finally {
                versionTemporary.delete()
            }
        }
    }
}
