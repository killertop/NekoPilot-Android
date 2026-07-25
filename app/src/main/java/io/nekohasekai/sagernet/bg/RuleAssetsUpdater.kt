package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * Manages the two built-in China rule_sets from source-controlled APK assets.
 *
 * Rule data determines direct/proxy routing and is therefore security-sensitive. Releases before
 * 2.3.10 accepted an unsigned mutable branch at runtime; this manager now only verifies and
 * atomically restores the APK-pinned pair in app-private storage.
 */
object RuleAssetsUpdater {

    private const val WORK_NAME = "RuleAssetsUpdater"
    private const val MAX_RULE_ASSET_BYTES = 16 * 1024 * 1024
    private const val PRIVATE_ASSET_DIRECTORY_NAME = "rule-assets"
    private const val SNAPSHOT_DIRECTORY_NAME = "rule-asset-snapshots"
    private const val REPAIR_SNAPSHOT_SUFFIX = ".repair."
    private val srsMagic = byteArrayOf('S'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), 1)
    private val updateMutex = Mutex()
    /** Prevents overlapping FileChannel locks from different threads in this process. */
    private val processFileLock = Any()

    enum class Asset(val fileName: String) {
        GEOIP("geoip-cn.srs"),
        GEOSITE("geosite-cn.srs"),
    }

    private val officialAssets = Asset.values().toList()

    enum class UpdateResult { UPDATED, UP_TO_DATE }
    enum class UpdatePhase { CHECKING, SWITCHING_SOURCE, DOWNLOADING, VERIFYING }

    data class UpdateProgress(
        val asset: Asset,
        val phase: UpdatePhase,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
    )

    /**
     * An immutable, private copy of both built-in rule sets used by one config transaction.
     *
     * Native config validation, isolated preflight and live start must all resolve the same
     * bytes. Callers therefore pass [directory] to the config compiler instead of the private
     * installation directory, whose files can be repaired while no snapshot is held.
     */
    internal data class RuntimeSnapshot(
        val directory: File,
        val fingerprint: String,
    )

    fun schedule() {
        // Cancel work scheduled by older releases. updateNow() remains safe for a stale worker
        // already executing, but new releases must never wake periodically to fetch rule data.
        RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
    }

    /** Installs the source-controlled SRS pair on first use or restores a damaged private copy. */
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

    private fun ensureBundledAssetsLocked(
        context: Context,
        assets: Collection<Asset> = officialAssets,
    ): Boolean {
        val assetsDirectory = assetsDirectory(context)
        check(assetsDirectory.exists() || assetsDirectory.mkdirs()) { "Unable to create rule asset directory" }
        var restored = false
        assets.forEach { asset ->
            val target = File(assetsDirectory, asset.fileName)
            val version = File(assetsDirectory, "${asset.fileNameWithoutExtension}.version.txt")
            val expectedDigest = bundledAssetDigest(context, asset)
            if (
                matchesRuleAssetDigest(target, expectedDigest) &&
                version.takeIf(File::isFile)?.readText()?.trim()?.lowercase() == expectedDigest
            ) {
                return@forEach
            }
            val temporary = File(assetsDirectory, ".${asset.fileName}.${UUID.randomUUID()}.bootstrap.tmp")
            try {
                context.assets.open("sing-box/${asset.fileName}.xz").use { compressed ->
                    XZInputStream(compressed).use { input ->
                        FileOutputStream(temporary).use { output ->
                            copyRuleAssetBounded(input, output)
                            output.fd.sync()
                        }
                    }
                }
                require(isValidRuleAsset(temporary)) {
                    "Bundled ${asset.fileName} is not a sing-box rule set"
                }
                require(matchesRuleAssetDigest(temporary, expectedDigest)) {
                    "Bundled ${asset.fileName} digest does not match its source-controlled sidecar"
                }
                // The files share one app-private filesystem, so Android's rename replaces the
                // previous file atomically. Never delete the old known-good file before the
                // decompressed replacement has passed validation and digest verification.
                check(temporary.renameTo(target)) { "Unable to install bundled ${asset.fileName}" }
                writeVersionAtomically(version, expectedDigest)
                restored = true
            } finally {
                temporary.delete()
            }
        }
        return restored
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
        val assets = requestedAsset?.let(::listOf) ?: officialAssets
        assets.forEach { asset -> onProgress(UpdateProgress(asset, UpdatePhase.VERIFYING)) }
        return if (ensureBundledAssetsLocked(context, assets)) {
            UpdateResult.UPDATED
        } else {
            UpdateResult.UP_TO_DATE
        }
    }

    private fun assetsDirectory(context: Context): File =
        File(context.filesDir, PRIVATE_ASSET_DIRECTORY_NAME)

    private fun bundledAssetDigest(context: Context, asset: Asset): String =
        context.assets.open("sing-box/${asset.fileNameWithoutExtension}.version.txt").bufferedReader().use {
            it.readText().trim().lowercase()
        }.also { digest ->
            require(isSha256Digest(digest)) { "Bundled ${asset.fileName} has an invalid SHA-256 sidecar" }
        }

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
            require(isValidRuleAsset(source)) {
                "${asset.fileName} is missing or has an invalid size"
            }
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
        if (target.exists()) {
            // Do not delete or overwrite a damaged published directory: an already-running core
            // may still hold it open. Reuse an earlier healthy repair generation or publish a
            // new immutable one alongside it so the next start/reload can recover safely.
            findHealthyRepairSnapshot(snapshotRoot, fingerprint, digests)?.let { repaired ->
                return RuntimeSnapshot(repaired, fingerprint)
            }
        }
        val publishTarget = if (target.exists()) {
            File(snapshotRoot, "$fingerprint$REPAIR_SNAPSHOT_SUFFIX${UUID.randomUUID()}")
        } else {
            target
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
            check(temporary.renameTo(publishTarget)) { "Unable to publish rule asset snapshot" }
            return RuntimeSnapshot(publishTarget, fingerprint)
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

    private fun findHealthyRepairSnapshot(
        snapshotRoot: File,
        fingerprint: String,
        digests: List<Pair<Asset, String>>,
    ): File? = snapshotRoot.listFiles()
        ?.asSequence()
        ?.filter { file ->
            file.isDirectory &&
                file.name.startsWith("$fingerprint$REPAIR_SNAPSHOT_SUFFIX") &&
                snapshotMatches(file, digests)
        }
        ?.maxByOrNull(File::lastModified)

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

    internal fun isValidRuleAsset(file: File): Boolean =
        file.isFile &&
            file.length() in 1..MAX_RULE_ASSET_BYTES.toLong() &&
            isSingBoxRuleSet(file)

    internal fun matchesRuleAssetDigest(file: File, expectedDigest: String): Boolean =
        isValidRuleAsset(file) && isSha256Digest(expectedDigest) && sha256(file) == expectedDigest.lowercase()

    internal fun copyRuleAssetBounded(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = MAX_RULE_ASSET_BYTES.toLong(),
    ) {
        require(maxBytes > 0)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            copied += read
            require(copied <= maxBytes) { "Rule asset exceeds maximum size" }
            output.write(buffer, 0, read)
        }
    }

    private fun isSha256Digest(value: String): Boolean =
        value.length == 64 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    private fun writeVersionAtomically(version: File, value: String) {
        val temporary = File(version.parentFile, ".${version.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(value)
            check(temporary.renameTo(version)) { "Unable to store ${version.name}" }
        } finally {
            temporary.delete()
        }
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
                UpdateResult.UPDATED -> Logs.i("Bundled rule sets restored")
                UpdateResult.UP_TO_DATE -> Logs.d("Bundled rule sets verified")
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

}
