package io.nekohasekai.sagernet.bg

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.sagernet.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide initialization required by the official gomobile libbox AAR. */
internal object OfficialLibboxRuntime {
    private val initialized = AtomicBoolean()

    fun ensureSetup(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        Libbox.setup(SetupOptions().apply {
            basePath = context.filesDir.absolutePath
            workingPath = context.filesDir.absolutePath
            tempPath = context.cacheDir.absolutePath
            fixAndroidStack = true
            debug = BuildConfig.DEBUG
        })
    }
}
