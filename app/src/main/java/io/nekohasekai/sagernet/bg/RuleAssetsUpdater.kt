package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
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
    private const val JSDELIVR_BASE_URL = "https://cdn.jsdelivr.net/gh"
    private val updateMutex = Mutex()

    enum class Asset(val fileName: String, internal val repo: String) {
        GEOIP("geoip.db", "SagerNet/sing-geoip"),
        GEOSITE("geosite.db", "SagerNet/sing-geosite"),
    }

    private val officialAssets = Asset.values().toList()

    enum class UpdateResult { UPDATED, UP_TO_DATE }
    enum class UpdatePhase { CHECKING, SWITCHING_SOURCE, DOWNLOADING, VERIFYING }

    private enum class ReleaseSource(val displayName: String, val timeoutMillis: Long) {
        GITHUB("GitHub Release", 20_000),
        JSDELIVR("jsDelivr mirror", 60_000),
    }

    private val releaseSources = ReleaseSource.values().toList()

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
            UPDATE,
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
    ): UpdateResult = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            val lockFile = File(context.filesDir, ".rule-assets-update.lock")
            RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
                randomAccessFile.channel.use { channel ->
                    val processLock = channel.lock()
                    try {
                        updateLocked(context, requestedAsset, onProgress)
                    } finally {
                        processLock.release()
                    }
                }
            }
        }
    }

    private fun updateLocked(
        context: Context,
        requestedAsset: Asset?,
        onProgress: (UpdateProgress) -> Unit,
    ): UpdateResult {
        val assetsDirectory = context.getExternalFilesDir(null) ?: context.filesDir
        check(assetsDirectory.exists() || assetsDirectory.mkdirs()) { "Unable to create rule asset directory" }
        cleanTemporaryFiles(assetsDirectory)

        val candidates = ArrayList<RuleAssetCandidate>()
        try {
            for (asset in requestedAsset?.let(::listOf) ?: officialAssets) {
                val target = File(assetsDirectory, asset.fileName)
                val version = File(assetsDirectory, asset.fileNameWithoutExtension + ".version.txt")
                val localVersion = version.takeIf(File::isFile)?.readText()?.trim().orEmpty()
                // Background updates preserve user-imported data. Selecting a default China
                // rule's update action is an explicit request to restore its official asset.
                if (localVersion == "Custom" && requestedAsset == null) continue

                val candidate = firstSuccessfulSource(
                    releaseSources,
                    onFallback = { failedSource, nextSource, error ->
                        Logs.w(
                            "${asset.fileName} update via ${failedSource.displayName} failed; " +
                                "trying ${nextSource.displayName}",
                            error,
                        )
                        onProgress(UpdateProgress(asset, UpdatePhase.SWITCHING_SOURCE))
                    },
                ) { source ->
                    val client = newHttpClient(source.timeoutMillis)
                    try {
                        if (source == ReleaseSource.GITHUB) {
                            onProgress(UpdateProgress(asset, UpdatePhase.CHECKING))
                        }
                        val release = fetchLatestRelease(client, asset, source)
                        if (release.version == localVersion && target.isFile) {
                            null
                        } else {
                            downloadCandidate(
                                client,
                                asset,
                                release,
                                assetsDirectory,
                                target,
                                version,
                                onProgress,
                            )
                        }
                    } finally {
                        client.close()
                    }
                }
                if (candidate != null) candidates += candidate
            }

            candidates.forEach(RuleAssetCandidate::install)
            return if (candidates.isEmpty()) UpdateResult.UP_TO_DATE else UpdateResult.UPDATED
        } finally {
            candidates.forEach { it.temporary.delete() }
        }
    }

    private fun cleanTemporaryFiles(assetsDirectory: File) {
        assetsDirectory.listFiles()?.forEach { file ->
            if (file.isFile && isTemporaryFileName(file.name)) file.delete()
        }
    }

    internal fun isTemporaryFileName(fileName: String): Boolean = officialAssets.any { asset ->
        val assetTemporary = fileName.startsWith(".${asset.fileName}.") &&
            fileName.endsWith(".download.tmp")
        val versionTemporary = fileName.startsWith(".${asset.fileNameWithoutExtension}.version.txt.") &&
            fileName.endsWith(".tmp")
        assetTemporary || versionTemporary
    }

    private fun newHttpClient(timeoutMillis: Long): libcore.HTTPClient =
        Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            setTimeout(timeoutMillis)
            // Prefer the active NekoPilot tunnel when it is available. The HTTP
            // client deliberately falls back to a direct connection when it is not.
            trySocks5(
                DataStore.mixedPort,
                DataStore.mixedProxyUsername,
                DataStore.mixedProxyPassword,
            )
        }

    private fun downloadCandidate(
        client: libcore.HTTPClient,
        asset: Asset,
        release: RuleRelease,
        assetsDirectory: File,
        target: File,
        version: File,
        onProgress: (UpdateProgress) -> Unit,
    ): RuleAssetCandidate {
        val temporary = File(
            assetsDirectory,
            ".${asset.fileName}.${UUID.randomUUID()}.download.tmp",
        )
        try {
            val response = client.newRequest().apply {
                setURL(release.downloadUrl)
                setUserAgent(USER_AGENT)
            }.execute()
            onProgress(UpdateProgress(asset, UpdatePhase.DOWNLOADING, totalBytes = release.size))
            response.writeToProgressLimited(
                temporary.canonicalPath,
                MAX_RULE_ASSET_BYTES.toLong(),
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
            val downloadedSize = temporary.length()
            require(
                temporary.isFile && downloadedSize in 1..MAX_RULE_ASSET_BYTES.toLong() &&
                    (release.size <= 0 || downloadedSize == release.size)
            ) {
                "${asset.fileName} has an invalid size"
            }
            onProgress(UpdateProgress(asset, UpdatePhase.VERIFYING))
            verifyChecksum(asset.fileName, temporary, release.checksum)
            Libcore.validateRuleAsset(asset.fileName, temporary.canonicalPath)
            return RuleAssetCandidate(target, version, temporary, release.version)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun fetchLatestRelease(
        client: libcore.HTTPClient,
        asset: Asset,
        source: ReleaseSource,
    ): RuleRelease = when (source) {
        ReleaseSource.GITHUB -> fetchGitHubRelease(client, asset)
        ReleaseSource.JSDELIVR -> fetchJsDelivrRelease(client, asset)
    }

    private fun fetchGitHubRelease(client: libcore.HTTPClient, asset: Asset): RuleRelease {
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
        val checksum = downloadChecksum(
            client,
            requireNotNull(checksumUrl) {
                "${asset.fileName} checksum is missing from release $tag"
            },
        )
        return RuleRelease(
            version = checksumVersion(asset.fileName, checksum),
            downloadUrl = requireNotNull(downloadUrl) {
                "${asset.fileName} is missing from release $tag"
            },
            size = downloadSize,
            checksum = checksum,
        )
    }

    private fun fetchJsDelivrRelease(client: libcore.HTTPClient, asset: Asset): RuleRelease {
        val baseUrl = "$JSDELIVR_BASE_URL/${asset.repo}@release"
        val checksum = downloadChecksum(client, "$baseUrl/${asset.fileName}.sha256sum")
        return RuleRelease(
            version = checksumVersion(asset.fileName, checksum),
            downloadUrl = "$baseUrl/${asset.fileName}",
            size = 0,
            checksum = checksum,
        )
    }

    private fun downloadChecksum(client: libcore.HTTPClient, url: String): ByteArray =
        client.newRequest().apply {
            setURL(url)
            setUserAgent(USER_AGENT)
        }.execute().content

    private fun checksumVersion(fileName: String, checksum: ByteArray): String =
        "sha256:${parseExpectedChecksum(fileName, checksum)}"

    private fun parseExpectedChecksum(fileName: String, checksum: ByteArray): String {
        require(checksum.isNotEmpty() && checksum.size <= MAX_CHECKSUM_BYTES) {
            "$fileName has an invalid checksum"
        }
        return checksum.decodeToString().trim().split(Regex("\\s+"))
            .firstOrNull()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: error("$fileName has an invalid checksum")
    }

    private fun verifyChecksum(fileName: String, content: File, checksum: ByteArray) {
        val expected = parseExpectedChecksum(fileName, checksum)
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logs.w(error)
            Result.retry()
        }
    }

    private val Asset.fileNameWithoutExtension: String
        get() = fileName.substringBeforeLast('.')

    private data class RuleRelease(
        val version: String,
        val downloadUrl: String,
        val size: Long,
        val checksum: ByteArray,
    )

    private data class RuleAssetCandidate(
        val target: File,
        val version: File,
        val temporary: File,
        val releaseVersion: String,
    ) {
        fun install() {
            check(temporary.renameTo(target)) { "Unable to install ${target.name}" }
            val versionTemporary = File(
                version.parentFile,
                ".${version.name}.${UUID.randomUUID()}.tmp",
            )
            try {
                versionTemporary.writeText(releaseVersion)
                check(versionTemporary.renameTo(version)) { "Unable to store ${target.name} version" }
            } finally {
                versionTemporary.delete()
            }
        }
    }
}

internal fun <S, T> firstSuccessfulSource(
    sources: List<S>,
    onFallback: (failedSource: S, nextSource: S, error: Throwable) -> Unit,
    attempt: (S) -> T,
): T {
    require(sources.isNotEmpty()) { "At least one update source is required" }
    sources.forEachIndexed { index, source ->
        try {
            return attempt(source)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val nextSource = sources.getOrNull(index + 1) ?: throw error
            onFallback(source, nextSource, error)
        }
    }
    error("No update source available")
}
