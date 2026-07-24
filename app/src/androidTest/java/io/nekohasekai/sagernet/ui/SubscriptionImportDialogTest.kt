package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.R
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionImportDialogTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var activityScenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        // ActivityScenario owns the target activity and its bottom sheet. Closing it directly
        // avoids global accessibility actions, which can block Android 15's instrumentation
        // process when a dialog window is still transitioning.
        activityScenario?.close()
        activityScenario = null
        instrumentation.runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(null, ""),
            )
        }
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun addSheetOffersOnlyQrAndClipboardImport() {
        launchMainActivity()
        openHomeImportMenu()

        assertNotNull(
            "QR import action was not visible",
            waitForNodeByViewId("${context.packageName}:id/add_scan_qr"),
        )
        assertNotNull(
            "Clipboard import action was not visible",
            waitForNodeByViewId("${context.packageName}:id/add_from_clipboard"),
        )
        instrumentation.uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun clipboardHttpUrlOpensSubscriptionImport() {
        launchMainActivity()
        instrumentation.runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(null, "https://example.com/subscription"),
            )
        }
        openHomeImportMenu()

        val clipboardImport = waitForNodeByViewId("${context.packageName}:id/add_from_clipboard")
        assertNotNull("Clipboard import action was not visible", clipboardImport)
        assertTrue(
            "Clipboard import action could not be opened",
            clickNodeOrParent(clipboardImport!!),
        )

        assertNotNull(
            "A standalone HTTPS URL was not routed to subscription import",
            waitForNodeByText(context.getString(R.string.subscription_import)),
        )
        instrumentation.uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        )
    }

    private fun openHomeImportMenu() {
        val opened = BooleanArray(1)
        activityScenario?.onActivity { activity ->
            val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
            opened[0] = toolbar.menu.performIdentifierAction(R.id.action_add, 0)
        }
        assertTrue("Add-profile action could not be opened on Home", opened[0])
    }

    private fun launchMainActivity() {
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        activityScenario?.onActivity { activity ->
            activity.supportFragmentManager.executePendingTransactions()
            assertTrue("Home toolbar was not initialized", activity.findViewById<Toolbar>(R.id.toolbar) != null)
        }
    }

    private fun waitForNodeByViewId(viewId: String): AccessibilityNodeInfo? =
        waitForValue(5_000) {
            instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId(viewId)
                ?.firstOrNull()
        }

    private fun waitForNodeByText(text: String): AccessibilityNodeInfo? =
        waitForValue(5_000) {
            findNodeByText(text)
        }

    private fun findNodeByText(text: String): AccessibilityNodeInfo? =
        instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(text)
            ?.firstOrNull { it.text?.toString() == text }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = candidate.parent
        }
        return false
    }

    private fun <T> waitForValue(timeoutMillis: Long, block: () -> T?): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            block()?.let { return it }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return null
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
