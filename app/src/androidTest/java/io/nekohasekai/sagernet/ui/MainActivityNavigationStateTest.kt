package io.nekohasekai.sagernet.ui

import android.os.ParcelFileDescriptor
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationStateTest {

    @Test
    fun secondaryPageKeepsBottomNavigationHiddenAfterRecreation() {
        bringTargetAppToForeground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.displaySecondaryFragment(AboutFragment())
                activity.supportFragmentManager.executePendingTransactions()
                assertFalse(activity.binding.bottomNavigation.isVisible)
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                assertFalse(activity.binding.bottomNavigation.isVisible)
            }
        }
    }

    /** HyperOS blocks a background instrumentation process from starting an activity. */
    private fun bringTargetAppToForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = "${context.packageName}/${MainActivity::class.java.name}"
        val result = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W --user current -n $component -f 0x10008000",
            )
        ).bufferedReader().use { it.readText() }
        assertTrue("MainActivity launch failed: $result", result.contains("Status: ok"))
        instrumentation.waitForIdleSync()
    }
}
