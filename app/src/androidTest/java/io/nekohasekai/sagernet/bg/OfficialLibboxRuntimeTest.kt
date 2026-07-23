package io.nekohasekai.sagernet.bg

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves that the APK ABI can load and initialize the official libbox bridge. */
@RunWith(AndroidJUnit4::class)
class OfficialLibboxRuntimeTest {
    @Test
    fun initializesPinnedOfficialLibbox() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        OfficialLibboxRuntime.ensureSetup(context)

        assertEquals("1.14.0-beta.1", Libbox.version())
        assertTrue(Libbox.goVersion().startsWith("go1.26.5"))
        val startOrReload = CommandServer::class.java.getMethod(
            "startOrReloadService",
            String::class.java,
            OverrideOptions::class.java,
        )
        assertEquals(Void.TYPE, startOrReload.returnType)
    }
}
