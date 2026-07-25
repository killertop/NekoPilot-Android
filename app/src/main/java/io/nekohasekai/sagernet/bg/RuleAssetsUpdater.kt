package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Updates the two built-in China rule_sets without tying rule data to an APK
 * release. Candidates are bounded, identified as sing-box SRS binaries, then atomically
 * replace the previous version. Full parsing remains the responsibility of libbox when the
 * service starts, so a failed download can never replace a known-good file by accident.
 */
object RuleAssetsUpdater {

    private const val WORK_NAME = "RuleAssetsUpdater"
    private const val UPDATE_INTERVAL_DAYS = 7L
    private const val FIRST_CHECK_DELAY_HOURS = 6L
    private const val MAX_RULE_ASSET_BYTES = 16 * 1024 * 1024
    private const val USER_AGENT = "NekoPilot-rule-set-updater"
    private const val SNAPSHOT_DIRECTORY_NAME = "rule-asset-snapshots"
    private val srsMagic = byteArrayOf('S'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), 1)
    private val updateMutex = Mutex()
    /** Prevents overlapping FileChannel locks from different threads in this process. */
    private val processFileLock = Any()

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

    /**
     * An immutable, private copy of both built-in rule sets used by one config transaction.
     *
     * The public external-assets directory is intentionally mutable so rule updates can arrive
     * without an APK release. Native config validation, isolated preflight and live start must
     * nevertheless all resolve the same bytes. Callers therefore pass [directory] to the config
     * compiler instead of the mutable source directory.
     */
    internal data class RuntimeSnapshot(
        val directory: File,
        val fingerprint: String,
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

    /** Installs bundled SRS files on first use without overwriting downloaded or custom assets. */
    fun ensureBundledAssets(context: Context) {
        withRuleAssetFileLock(context) { ensureBundledAssetsLocked(context) }
    }

    /**
     * Captures both rule sets under the same cross-process lock used by updates. The returned
     * directory is content-addressed and never modified, so an updater cannot change files
     * between `Libbox.checkConfig`, preflight, and the eventual live start.
     */
    internal fun runtimeSnapshot(context: Context): RuntimeSnapshot = withRuleAssetFileLock(context) {
        ensureBundledAssetsLocked(context)
        materializeRuntimeSnapshot(
            sourceDirectory = assetsDirectory(context),
            snapshotRoot = File(context.filesDir, SNAPSHOT_DIRECTORY_NAME),
        )
    }

    private fun ensureBundledAssetsLocked(context: Context) {
        val assetsDirectory = assetsDirectory(context)
        check(assetsDirectory.exists() || assetsDirectory.mkdirs()) { "Unable to create rule asset directory" }
        officialAssets.forEach { asset ->
            val target = File(assetsDirectory, asset.fileName)
            if (target.isFile && target.length() > 0) return@forEach
            val temporary = File(assetsDirectory, ".${asset.fileName}.${UUID.randomUUID()}.bootstrap.tmp")
            try {
                context.assets.open("sing-box/${asset.fileName}.xz").use { compressed ->
                    XZInputStream(compressed).use { input ->
                        temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                }
                require(temporary.length() in 1..MAX_RULE_ASSET_BYTES.toLong()) {
                    "Bundled ${asset.fileName} has an invalid size"
                }
                require(isSingBoxRuleSet(temporary)) {
                    "Bundled ${asset.fileName} is not a sing-box rule set"
                }
                check(temporary.renameTo(target)) { "Unable to install bundled ${asset.fileName}" }
                context.assets.open("sing-box/${asset.fileNameWithoutExtension}.version.txt").bufferedReader().use {
                    File(assetsDirectory, "${asset.fileNameWithoutExtension}.version.txt").writeText(it.readText().trim())
                }
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun updateNow(
        context: Context,
        requestedAsset: Asset? = null,
        onProgress: (UpdateProgress) -> Unit = {},
    ): UpdateResult = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            withRuleAssetFileLock(context) {
                updateLocked(context, requestedAsset, onProgress)
            }
        }
    }

    private fun updateLocked(
        context: Context,
        requestedAsset: Asset?,
        onProgress: (UpdateProgress) -> Unit,
    ): UpdateResult {
        val assetsDirectory = assetsDirectory(context)
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
                    downloadCandidate(
                        newHttpClient(source.timeoutMillis),
                        source,
                        asset,
                        target,
                        version,
                        localVersion,
                        assetsDirectory,
                        onProgress,
                    )
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

    private fun assetsDirectory(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    /**
     * A process-local monitor is required in addition to the OS file lock: Java rejects two
     * overlapping locks held by the same process before it can serialize them at the filesystem.
     */
    private inline fun <T> withRuleAssetFileLock(context: Context, block: () -> T): T =
        synchronized(processFileLock) {
            val lockFile = File(context.filesDir, ".rule-assets-update.lock")
            RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
                randomAccessFile.channel.use { channel ->
                    val lock = channel.lock()
                    try {
                        block()
                    } finally {
                        lock.release()
                    }
                }
            }
        }

    /**
     * Copies the entire currently published pair into a content-addressed private directory.
     * Kept internal so JVM tests can exercise the crash/race boundary without an Android Context.
     */
    internal fun materializeRuntimeSnapshot(
        sourceDirectory: File,
        snapshotRoot: File,
    ): RuntimeSnapshot {
        val digests = officialAssets.map { asset ->
            val source = File(sourceDirectory, asset.fileName)
            require(source.isFile && source.length() in 1..MAX_RULE_ASSET_BYTES.toLong()) {
                "${asset.fileName} is missing or has an invalid size"
            }
            require(isSingBoxRuleSet(source)) { "${asset.fileName} is not a sing-box rule set" }
            asset to sha256(source)
        }
        check(snapshotRoot.exists() || snapshotRoot.mkdirs()) {
            "Unable to create rule asset snapshot directory"
        }
        cleanAbandonedSnapshotDirectories(snapshotRoot)
        val fingerprint = snapshotFingerprint(digests)
        val target = File(snapshotRoot, fingerprint)
        if (target.isDirectory && snapshotMatches(target, digests)) {
            return RuntimeSnapshot(target, fingerprint)
        }
        require(!target.exists()) {
            "Rule asset snapshot $fingerprint is corrupted; refusing to reuse mutable rule data"
        }

        val temporary = File(snapshotRoot, ".${fingerprint}.${UUID.randomUUID()}.snapshot.tmp")
        check(temporary.mkdirs()) { "Unable to stage rule asset snapshot" }
        try {
            digests.forEach { (asset, digest) ->
                val source = File(sourceDirectory, asset.fileName)
                val copied = File(temporary, asset.fileName)
                FileInputStream(source).use { input ->
                    FileOutputStream(copied).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                require(snapshotFileMatches(copied, digest)) {
                    "Unable to verify copied ${asset.fileName} rule asset"
                }
            }
            check(temporary.renameTo(target)) { "Unable to publish rule asset snapshot" }
            return RuntimeSnapshot(target, fingerprint)
        } finally {
            if (temporary.exists()) temporary.deleteRecursively()
        }
    }

    private fun snapshotFingerprint(digests: List<Pair<Asset, String>>): String =
        MessageDigest.getInstance("SHA-256").let { digest ->
            digests.forEach { (asset, assetDigest) ->
                digest.update(asset.fileName.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(assetDigest.toByteArray(Charsets.US_ASCII))
                digest.update(0.toByte())
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

    private fun snapshotMatches(directory: File, digests: List<Pair<Asset, String>>): Boolean =
        digests.all { (asset, digest) -> snapshotFileMatches(File(directory, asset.fileName), digest) }

    private fun snapshotFileMatches(file: File, expectedDigest: String): Boolean =
        file.isFile &&
            file.length() in 1..MAX_RULE_ASSET_BYTES.toLong() &&
            isSingBoxRuleSet(file) &&
            sha256(file) == expectedDigest

    private fun cleanAbandonedSnapshotDirectories(snapshotRoot: File) {
        snapshotRoot.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith('.') && file.name.endsWith(".snapshot.tmp")) {
                file.deleteRecursively()
            }
        }
    }

    internal fun isTemporaryFileName(fileName: String): Boolean = officialAssets.any { asset ->
        val assetTemporary = fileName.startsWith(".${asset.fileName}.") &&
            fileName.endsWith(".download.tmp")
        val versionTemporary = fileName.startsWith(".${asset.fileNameWithoutExtension}.version.txt.") &&
            fileName.endsWith(".tmp")
        assetTemporary || versionTemporary
    }

    private fun newHttpClient(timeoutMillis: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .useActiveVpnProxy()
        .build()

    private fun downloadCandidate(
        client: OkHttpClient,
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
            val request = Request.Builder().url(source.url(asset)).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "${asset.fileName} returned HTTP ${response.code}" }
                val total = response.body?.contentLength()?.takeIf { it >= 0 } ?: 0L
                require(total <= MAX_RULE_ASSET_BYTES) { "${asset.fileName} exceeds maximum size" }
                onProgress(UpdateProgress(asset, UpdatePhase.DOWNLOADING, 0, total))
                response.body?.byteStream()?.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            downloaded += read
                            require(downloaded <= MAX_RULE_ASSET_BYTES) { "${asset.fileName} exceeds maximum size" }
                            output.write(buffer, 0, read)
                            onProgress(UpdateProgress(asset, UpdatePhase.DOWNLOADING, downloaded, total))
                        }
                    }
                } ?: error("${asset.fileName} returned an empty body")
            }
            require(temporary.isFile && temporary.length() in 1..MAX_RULE_ASSET_BYTES.toLong()) {
                "${asset.fileName} has an invalid size"
            }
            onProgress(UpdateProgress(asset, UpdatePhase.VERIFYING))
            require(isSingBoxRuleSet(temporary)) { "${asset.fileName} is not a sing-box rule set" }
            val digest = sha256(temporary)
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

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    internal fun isSingBoxRuleSet(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val header = ByteArray(srsMagic.size)
            input.read(header) == header.size && header.contentEquals(srsMagic)
        }
    }.getOrDefault(false)

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
