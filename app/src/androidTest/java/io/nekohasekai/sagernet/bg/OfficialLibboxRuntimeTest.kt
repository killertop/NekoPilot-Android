package io.nekohasekai.sagernet.bg

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.libbox.Libbox
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

        assertTrue(Libbox.version().startsWith("1.14.0-alpha.48"))
    }
}
