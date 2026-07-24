package io.nekohasekai.sagernet.bg

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.sagernet.BuildConfig
import java.io.File

/** Process-wide initialization required by the official gomobile libbox AAR. */
internal object OfficialLibboxRuntime {
    private val setupLock = Any()

    @Volatile
    private var initialized = false

    /**
     * [runtimeDirectory] must be process-private for independently running command servers.
     * The production VPN uses the app files directory; an isolated preflight process uses its
     * own child directory so its command socket and config snapshot cannot disturb the live VPN.
     */
    fun ensureSetup(context: Context, runtimeDirectory: File = context.filesDir) {
        if (initialized) return
        synchronized(setupLock) {
            if (initialized) return
            require(runtimeDirectory.isDirectory || runtimeDirectory.mkdirs()) {
                "Unable to create libbox runtime directory"
            }
            val tempDirectory = if (runtimeDirectory.absoluteFile == context.filesDir.absoluteFile) {
                context.cacheDir
            } else {
                File(runtimeDirectory, "tmp").apply {
                    require(isDirectory || mkdirs()) { "Unable to create libbox temp directory" }
                }
            }
            Libbox.setup(SetupOptions().apply {
                basePath = runtimeDirectory.absolutePath
                workingPath = runtimeDirectory.absolutePath
                tempPath = tempDirectory.absolutePath
                fixAndroidStack = true
                debug = BuildConfig.DEBUG
            })
            // Do not permanently poison the process after a transient setup failure.
            initialized = true
        }
    }
}
