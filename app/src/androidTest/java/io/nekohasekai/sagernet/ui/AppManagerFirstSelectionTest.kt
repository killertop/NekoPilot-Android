package io.nekohasekai.sagernet.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppManagerFirstSelectionTest {

    @Test
    fun secondaryActivityUsesConfiguredAppLocale() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val localeManager = io.nekohasekai.sagernet.SagerNet.application
            .getSystemService(LocaleManager::class.java)
        val previousLocales = localeManager.applicationLocales
        val previousSetupDone = DataStore.appProxySetupDone
        try {
            localeManager.applicationLocales = LocaleList.forLanguageTags("en-US")
            DataStore.appProxySetupDone = true
            ActivityScenario.launch(AppManagerActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("Per-app proxy", activity.getString(R.string.proxied_apps))
                    assertEquals("Per-app proxy", activity.supportActionBar?.title?.toString())
                }
            }
        } finally {
            DataStore.appProxySetupDone = previousSetupDone
            localeManager.applicationLocales = previousLocales
        }
    }

    @Test
    fun firstRecommendationsStayLocalUntilTheUserAppliesThem() {
        val previousProxyApps = DataStore.proxyApps
        val previousIndividual = DataStore.individual
        val previousSetupDone = DataStore.appProxySetupDone
        val previousShowSystemApps = DataStore.appProxyShowSystemApps
        try {
            DataStore.proxyApps = false
            DataStore.individual = ""
            DataStore.appProxySetupDone = false
            DataStore.appProxyShowSystemApps = true

            ActivityScenario.launch(AppManagerActivity::class.java).use { scenario ->
                // Recreate immediately, while the package list may still be loading. The
                // recommendation-pending flag must survive even when the draft is still empty.
                scenario.recreate()
                val ready = waitForUi(scenario) { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.list)
                    val layoutManager = list.layoutManager as? LinearLayoutManager
                    ?: return@waitForUi false
                    val first = layoutManager.findViewByPosition(0) ?: return@waitForUi false
                    val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
                    val apply = toolbar.menu.findItem(R.id.action_apply_app_policy)
                    !DataStore.proxyApps &&
                        !DataStore.appProxySetupDone &&
                        DataStore.individual.isBlank() &&
                        apply?.isVisible == true &&
                        layoutManager.findFirstVisibleItemPosition() == 0 &&
                        first.findViewById<SwitchCompat>(R.id.itemcheck)?.isChecked == true
                }
                assertTrue(
                    "Recommended apps were not prepared as a local draft at the top of the list",
                    ready,
                )

                scenario.recreate()
                assertTrue(
                    "The prepared recommendation draft did not survive recreation",
                    waitForUi(scenario) { activity ->
                        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
                        !DataStore.proxyApps &&
                            DataStore.individual.isBlank() &&
                            toolbar.menu.findItem(R.id.action_apply_app_policy)?.isVisible == true
                    },
                )

                scenario.onActivity { activity ->
                    val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
                    activity.onOptionsItemSelected(
                        requireNotNull(toolbar.menu.findItem(R.id.action_apply_app_policy)),
                    )
                }
                scenario.recreate()

                assertTrue(
                    "Applying the prepared app policy did not survive recreation",
                    waitForUi(scenario) { activity ->
                        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
                        DataStore.proxyApps &&
                            DataStore.appProxySetupDone &&
                            DataStore.individual.isNotBlank() &&
                            toolbar.menu.findItem(R.id.action_apply_app_policy)?.isVisible == false
                    },
                )
            }
        } finally {
            DataStore.proxyApps = previousProxyApps
            DataStore.individual = previousIndividual
            DataStore.appProxySetupDone = previousSetupDone
            DataStore.appProxyShowSystemApps = previousShowSystemApps
        }
    }

    private fun waitForUi(
        scenario: ActivityScenario<AppManagerActivity>,
        timeoutMillis: Long = 10_000,
        predicate: (AppManagerActivity) -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            val matched = AtomicBoolean(false)
            scenario.onActivity { matched.set(predicate(it)) }
            if (matched.get()) return true
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }
}
