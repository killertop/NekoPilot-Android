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

    enum class Asset(val fileName: String, internal val repo: String) {
        GEOIP("geoip.db", "SagerNet/sing-geoip"),
        GEOSITE("geosite.db", "SagerNet/sing-geosite"),
    }

    private val officialAssets = Asset.values().toList()

    enum class UpdateResult { UPDATED, UP_TO_DATE }
    enum class UpdatePhase { CHECKING, DOWNLOADING, VERIFYING }

    data class UpdateProgress(
        val asset: Asset,
        val phase: UpdatePhase,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
    )

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

    suspend fun updateNow(
        context: Context,
        requestedAsset: Asset? = null,
        onProgress: (UpdateProgress) -> Unit = {},
    ): UpdateResult {
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
            for (asset in requestedAsset?.let(::listOf) ?: officialAssets) {
                val target = File(assetsDirectory, asset.fileName)
                val version = File(assetsDirectory, asset.fileNameWithoutExtension + ".version.txt")
                val localVersion = version.takeIf(File::isFile)?.readText()?.trim().orEmpty()
                // Background updates preserve user-imported data. Selecting a default China
                // rule's update action is an explicit request to restore its official asset.
                if (localVersion == "Custom" && requestedAsset == null) continue

                onProgress(UpdateProgress(asset, UpdatePhase.CHECKING))
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
                    onProgress(
                        UpdateProgress(asset, UpdatePhase.DOWNLOADING, totalBytes = release.size)
                    )
                    response.writeToProgress(
                        temporary.canonicalPath,
                        object : libcore.HTTPProgress {
                            override fun onProgress(downloaded: Long, total: Long) {
                                onProgress(
                                    UpdateProgress(
                                        asset,
                                        UpdatePhase.DOWNLOADING,
                                        downloaded,
                                        total.takeIf { it > 0 } ?: release.size,
                                    )
                                )
                            }
                        },
                    )
                    require(
                        temporary.isFile && temporary.length() == release.size &&
                            temporary.length() <= MAX_RULE_ASSET_BYTES
                    ) {
                        "${asset.fileName} has an invalid size"
                    }
                    val checksum = client.newRequest().apply {
                        setURL(release.checksumUrl)
                        setUserAgent(USER_AGENT)
                    }.execute().content
                    onProgress(UpdateProgress(asset, UpdatePhase.VERIFYING))
                    verifyChecksum(asset.fileName, temporary, checksum)
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

    private fun fetchLatestRelease(client: libcore.HTTPClient, asset: Asset): RuleRelease {
        val response = client.newRequest().apply {
            setURL("https://api.github.com/repos/${asset.repo}/releases/latest")
            setUserAgent(USER_AGENT)
        }.execute()
        val release = JSONObject(Util.getStringBox(response.contentString))
        val tag = release.optString("tag_name").trim()
        require(tag.isNotBlank() && tag.length <= 128) { "Invalid ${asset.fileName} release version" }
        val releaseAssets = release.getJSONArray("assets")
        var downloadUrl: String? = null
        var downloadSize = 0L
        var checksumUrl: String? = null
        for (index in 0 until releaseAssets.length()) {
            val releaseAsset = releaseAssets.optJSONObject(index) ?: continue
            val url = releaseAsset.optString("browser_download_url")
                .takeIf { it.startsWith("https://") }
                ?: continue
            when (releaseAsset.optString("name")) {
                asset.fileName -> {
                    downloadUrl = url
                    downloadSize = releaseAsset.optLong("size")
                }
                "${asset.fileName}.sha256sum" -> checksumUrl = url
            }
        }
        require(downloadSize in 1..MAX_RULE_ASSET_BYTES.toLong()) {
            "${asset.fileName} has an invalid release size"
        }
        return RuleRelease(
            tag,
            requireNotNull(downloadUrl) { "${asset.fileName} is missing from release $tag" },
            requireNotNull(checksumUrl) { "${asset.fileName} checksum is missing from release $tag" },
            downloadSize,
        )
    }

    private fun verifyChecksum(fileName: String, content: File, checksum: ByteArray) {
        require(checksum.isNotEmpty() && checksum.size <= MAX_CHECKSUM_BYTES) {
            "$fileName has an invalid checksum"
        }
        val expected = checksum.decodeToString().trim().split(Regex("\\s+"))
            .firstOrNull()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: error("$fileName has an invalid checksum")
        val digest = MessageDigest.getInstance("SHA-256")
        content.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest()
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

    private val Asset.fileNameWithoutExtension: String
        get() = fileName.substringBeforeLast('.')

    private data class RuleRelease(
        val tag: String,
        val downloadUrl: String,
        val checksumUrl: String,
        val size: Long,
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
