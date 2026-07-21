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
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Updates the two built-in China rule_sets without tying rule data to an APK
 * release. Every downloaded file is parsed by the linked sing-box SRS reader
 * before atomically replacing the previous version.
 */
object RuleAssetsUpdater {

    private const val WORK_NAME = "RuleAssetsUpdater"
    private const val UPDATE_INTERVAL_DAYS = 7L
    private const val FIRST_CHECK_DELAY_HOURS = 6L
    private const val MAX_RULE_ASSET_BYTES = 16 * 1024 * 1024
    private const val USER_AGENT = "NekoPilot-rule-set-updater"
    private val updateMutex = Mutex()

    enum class Asset(val fileName: String, internal val repository: String) {
        GEOIP("geoip-cn.srs", "SagerNet/sing-geoip"),
        GEOSITE("geosite-cn.srs", "SagerNet/sing-geosite"),
    }

    private val officialAssets = Asset.values().toList()

    enum class UpdateResult { UPDATED, UP_TO_DATE }
    enum class UpdatePhase { CHECKING, SWITCHING_SOURCE, DOWNLOADING, VERIFYING }

    private enum class RuleSetSource(
        val displayName: String,
        val timeoutMillis: Long,
    ) {
        GITHUB("GitHub", 20_000),
        JSDELIVR("jsDelivr mirror", 60_000),
        ;

        fun url(asset: Asset): String = when (this) {
            GITHUB -> "https://raw.githubusercontent.com/${asset.repository}/rule-set/${asset.fileName}"
            JSDELIVR -> "https://cdn.jsdelivr.net/gh/${asset.repository}@rule-set/${asset.fileName}"
        }
    }

    private val ruleSetSources = RuleSetSource.values().toList()

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
                .build(),
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
                // Periodic maintenance never replaces a user-managed file. A tap on the
                // built-in rule's "Update" action is an explicit restore to official data.
                if (localVersion == "Custom" && requestedAsset == null) continue

                val candidate = firstSuccessfulSource(
                    ruleSetSources,
                    onFallback = { failedSource, nextSource, error ->
                        Logs.w(
                            "${asset.fileName} update via ${failedSource.displayName} failed; " +
                                "trying ${nextSource.displayName}",
                            error,
                        )
                        onProgress(UpdateProgress(asset, UpdatePhase.SWITCHING_SOURCE))
                    },
                ) { source ->
                    onProgress(UpdateProgress(asset, UpdatePhase.CHECKING))
                    val client = newHttpClient(source.timeoutMillis)
                    try {
                        downloadCandidate(client, source, asset, target, version, localVersion, assetsDirectory, onProgress)
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
            // Prefer the running tunnel, while still permitting first-run updates before
            // any node has been connected.
            trySocks5(
                DataStore.mixedPort,
                DataStore.mixedProxyUsername,
                DataStore.mixedProxyPassword,
            )
        }

    private fun downloadCandidate(
        client: libcore.HTTPClient,
        source: RuleSetSource,
        asset: Asset,
        target: File,
        version: File,
        localVersion: String,
        assetsDirectory: File,
        onProgress: (UpdateProgress) -> Unit,
    ): RuleAssetCandidate? {
        val temporary = File(
            assetsDirectory,
            ".${asset.fileName}.${UUID.randomUUID()}.download.tmp",
        )
        try {
            val response = client.newRequest().apply {
                setURL(source.url(asset))
                setUserAgent(USER_AGENT)
            }.execute()
            onProgress(UpdateProgress(asset, UpdatePhase.DOWNLOADING))
            response.writeToProgressLimited(
                temporary.canonicalPath,
                MAX_RULE_ASSET_BYTES.toLong(),
                object : libcore.HTTPProgress {
                    override fun onProgress(downloaded: Long, total: Long) {
                        onProgress(UpdateProgress(asset, UpdatePhase.DOWNLOADING, downloaded, total))
                    }
                },
            )
            require(temporary.isFile && temporary.length() in 1..MAX_RULE_ASSET_BYTES.toLong()) {
                "${asset.fileName} has an invalid size"
            }
            onProgress(UpdateProgress(asset, UpdatePhase.VERIFYING))
            val digest = Libcore.ruleAssetDigest(asset.fileName, temporary.canonicalPath)
            if (target.isFile && localVersion == digest) {
                temporary.delete()
                return null
            }
            return RuleAssetCandidate(target, version, temporary, digest)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    class UpdateTask(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result = try {
            when (updateNow(applicationContext)) {
                UpdateResult.UPDATED -> Logs.i("Rule sets updated")
                UpdateResult.UP_TO_DATE -> Logs.d("Rule sets already current")
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

    private data class RuleAssetCandidate(
        val target: File,
        val version: File,
        val temporary: File,
        val digest: String,
    ) {
        fun install() {
            check(temporary.renameTo(target)) { "Unable to install ${target.name}" }
            val versionTemporary = File(
                version.parentFile,
                ".${version.name}.${UUID.randomUUID()}.tmp",
            )
            try {
                versionTemporary.writeText(digest)
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
    require(sources.isNotEmpty()) { "At least one update source available" }
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
    error("No rule-set source available")
}
