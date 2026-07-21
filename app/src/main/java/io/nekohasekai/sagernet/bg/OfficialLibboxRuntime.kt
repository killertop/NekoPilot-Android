package io.nekohasekai.sagernet.bg

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.sagernet.BuildConfig

/** Process-wide initialization required by the official gomobile libbox AAR. */
internal object OfficialLibboxRuntime {
    private val setupLock = Any()

    @Volatile
    private var initialized = false

    fun ensureSetup(context: Context) {
        if (initialized) return
        synchronized(setupLock) {
            if (initialized) return
            Libbox.setup(SetupOptions().apply {
                basePath = context.filesDir.absolutePath
                workingPath = context.filesDir.absolutePath
                tempPath = context.cacheDir.absolutePath
                fixAndroidStack = true
                debug = BuildConfig.DEBUG
            })
            // Do not permanently poison the process after a transient setup failure.
            initialized = true
        }
    }
}
