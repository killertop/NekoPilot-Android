package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionImportDialogTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun addSheetOffersOnlyQrAndClipboardImport() {
        launchMainActivity()
        openHomeImportMenu()

        val add = waitForNodeByViewId("${context.packageName}:id/action_add")
        assertNotNull("Add-profile action was not visible", add)
        assertTrue("Add-profile action could not be opened", clickNodeOrParent(add!!))

        assertNotNull(
            "QR import action was not visible",
            waitForNodeByText(context.getString(R.string.add_profile_methods_scan_qr_code)),
        )
        assertNotNull(
            "Clipboard import action was not visible",
            waitForNodeByText(context.getString(R.string.action_import)),
        )
        instrumentation.uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        )
    }

    @Test
    fun clipboardHttpUrlOpensSubscriptionImport() {
        launchMainActivity()
        openHomeImportMenu()
        instrumentation.runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(null, "https://example.com/subscription"),
            )
        }

        val add = waitForNodeByViewId("${context.packageName}:id/action_add")
        assertNotNull("Add-profile action was not visible", add)
        assertTrue("Add-profile action could not be opened", clickNodeOrParent(add!!))

        val clipboardImport = waitForNodeByText(context.getString(R.string.action_import))
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
        assertNotNull(
            "Add-profile action was not visible on Home",
            waitForNodeByViewId("${context.packageName}:id/action_add"),
        )
    }

    private fun launchMainActivity() {
        val component = "${context.packageName}/${MainActivity::class.java.name}"
        val result = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W --user current -n $component -f 0x14000000",
            )
        ).bufferedReader().use { it.readText() }
        assertTrue("MainActivity launch failed: $result", result.contains("Status: ok"))
        instrumentation.waitForIdleSync()
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
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
