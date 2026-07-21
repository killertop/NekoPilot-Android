package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationStateTest {

    @Test
    fun nodeSourcesIsAVisiblePrimaryBottomTab() {
        bringTargetAppToForeground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.displayFragmentWithId(io.nekohasekai.sagernet.R.id.nav_nodes))
                activity.supportFragmentManager.executePendingTransactions()

                assertTrue(activity.binding.bottomNavigation.isVisible)
                assertTrue(
                    activity.binding.bottomNavigation.menu
                        .findItem(io.nekohasekai.sagernet.R.id.nav_nodes)
                        .isChecked,
                )
                assertTrue(
                    activity.supportFragmentManager
                        .findFragmentById(io.nekohasekai.sagernet.R.id.fragment_holder) is GroupFragment,
                )
            }
        }
    }

    @Test
    fun addNodeActionLivesInNodeSourcesInsteadOfHome() {
        bringTargetAppToForeground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                val homeToolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(
                    io.nekohasekai.sagernet.R.id.toolbar,
                )
                assertTrue(homeToolbar.menu.findItem(io.nekohasekai.sagernet.R.id.action_add) == null)

                activity.displayFragmentWithId(io.nekohasekai.sagernet.R.id.nav_nodes)
                activity.supportFragmentManager.executePendingTransactions()
                val nodeToolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(
                    io.nekohasekai.sagernet.R.id.toolbar,
                )
                assertTrue(nodeToolbar.menu.findItem(io.nekohasekai.sagernet.R.id.action_add) != null)
            }
        }
    }

    @Test
    fun systemBarInsetsKeepAppChromeClearAndAnchorSnackbar() {
        bringTargetAppToForeground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                ViewCompat.requestApplyInsets(activity.binding.root)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val rootInsets = checkNotNull(ViewCompat.getRootWindowInsets(activity.binding.root))
                val navigationInset = rootInsets
                    .getInsets(WindowInsetsCompat.Type.navigationBars())
                    .bottom
                val navigationContentHeight = activity.resources.getDimensionPixelSize(
                    io.nekohasekai.sagernet.R.dimen.main_bottom_navigation_content_height,
                )
                assertEquals(
                    navigationContentHeight + navigationInset,
                    activity.binding.bottomNavigation.height,
                )
                assertEquals(
                    navigationContentHeight + navigationInset,
                    activity.binding.fragmentHolder.paddingBottom,
                )
                assertTrue(
                    "Bottom navigation must reserve the system navigation area",
                    activity.binding.bottomNavigation.paddingBottom >= navigationInset,
                )

                val snackbar = activity.snackbar("Inset test")
                assertSame(activity.binding.bottomNavigation, snackbar.anchorView)
                snackbar.dismiss()

                if (Build.VERSION.SDK_INT >= 35) {
                    val appBar = activity.findViewById<com.google.android.material.appbar.AppBarLayout>(
                        io.nekohasekai.sagernet.R.id.appbar,
                    )
                    val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(
                        io.nekohasekai.sagernet.R.id.toolbar,
                    )
                    val topInset = rootInsets.getInsets(
                        WindowInsetsCompat.Type.statusBars() or
                            WindowInsetsCompat.Type.displayCutout(),
                    ).top
                    assertEquals(topInset, appBar.paddingTop)
                    assertEquals(toolbar.height + topInset, appBar.height)
                }
            }
        }
    }

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

    @Test
    fun externalImportIntentIsConsumedBeforeRecreation() {
        bringTargetAppToForeground()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("sn://subscription?url=https%3A%2F%2Fexample.com%2Fsubscription")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(Intent.ACTION_VIEW, activity.intent.action)
                assertEquals(intent.data, activity.intent.data)
                assertTrue(activity.intent.getBooleanExtra("main.extra_view_intent_dispatched", false))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(Intent.ACTION_VIEW, activity.intent.action)
                assertEquals(intent.data, activity.intent.data)
                assertTrue(activity.intent.getBooleanExtra("main.extra_view_intent_dispatched", false))
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
