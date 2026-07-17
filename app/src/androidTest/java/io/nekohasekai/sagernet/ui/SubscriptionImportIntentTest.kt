package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end coverage for a subscription supplied at test time. Keeping the link in an
 * instrumentation argument lets a real provider be checked without storing credentials in source.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionImportIntentTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun importsSubscriptionPassedAtRuntime() {
        val link = InstrumentationRegistry.getArguments().getString(ARG_SUBSCRIPTION_URI)
        assumeTrue("No subscription URI supplied", !link.isNullOrBlank())

        launchMainActivity()

        val databasePath = context.getDatabasePath(Key.DB_PROFILE)
        val originalGroupIds = waitForValue<Set<Long>>(5_000) {
            if (!databasePath.exists()) return@waitForValue null
            SQLiteDatabase.openDatabase(
                databasePath.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                database.rawQuery("SELECT id FROM proxy_groups", null).use { cursor ->
                    mutableSetOf<Long>().apply {
                        while (cursor.moveToNext()) add(cursor.getLong(0))
                    }.toSet()
                }
            }
        }
        assertNotNull("Profile database was not initialized", originalGroupIds)

        launchMainActivity(Uri.parse(link))

        val importTitle = context.getString(R.string.subscription_import)
        val positiveButton = waitForValue(8_000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitForValue null
            if (root.findAccessibilityNodeInfosByText(importTitle).isEmpty()) return@waitForValue null
            root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
        }
        assertNotNull("Subscription confirmation was not shown", positiveButton)
        assertTrue(
            "Subscription confirmation could not be accepted",
            positiveButton!!.performAction(AccessibilityNodeInfo.ACTION_CLICK),
        )
        acceptUpdateWarningIfShown()

        val importedGroupId = waitForValue(10_000) {
            findNewSubscriptionGroup(originalGroupIds!!)
        }
        assertNotNull("Subscription group was not created", importedGroupId)

        val profileCount = waitForValue(30_000) {
            SQLiteDatabase.openDatabase(
                databasePath.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                database.rawQuery(
                    "SELECT COUNT(*) FROM proxy_entities WHERE groupId = ?",
                    arrayOf(importedGroupId.toString()),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0).takeIf { it > 0 }
                }
            }
        }
        assertNotNull("Subscription update did not add any profiles", profileCount)
    }

    private fun findNewSubscriptionGroup(originalGroupIds: Set<Long>): Long? {
        val databasePath = context.getDatabasePath(Key.DB_PROFILE)
        if (!databasePath.exists()) return null
        return SQLiteDatabase.openDatabase(
            databasePath.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery(
                "SELECT id FROM proxy_groups WHERE type = ? ORDER BY id DESC",
                arrayOf(GroupType.SUBSCRIPTION.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getLong(0).takeIf { it !in originalGroupIds }?.let { return@use it }
                }
                null
            }
        }
    }

    private fun acceptUpdateWarningIfShown() {
        val warning = context.getString(R.string.update_subscription_warning)
        val positiveButton = waitForValue(3_000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitForValue null
            if (root.findAccessibilityNodeInfosByText(warning).isEmpty()) return@waitForValue null
            root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
        } ?: return
        assertTrue(
            "Subscription update warning could not be accepted",
            positiveButton.performAction(AccessibilityNodeInfo.ACTION_CLICK),
        )
    }

    /**
     * HyperOS blocks background activity starts issued by the instrumented app process. A shell
     * launch is still a real exported-intent flow and keeps this end-to-end test deterministic on
     * both vendor devices and AOSP devices.
     */
    private fun launchMainActivity(data: Uri? = null) {
        val component = "${context.packageName}/${MainActivity::class.java.name}"
        val command = buildString {
            append("am start -W --user current -n ")
            append(component)
            append(" -f ")
            append(if (data == null) "0x10008000" else "0x34000000")
            if (data != null) {
                append(" -a ")
                append(Intent.ACTION_VIEW)
                append(" -d ")
                append(data.toString())
            }
        }
        val result = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        assertTrue("MainActivity launch failed: $result", result.contains("Status: ok"))
        instrumentation.waitForIdleSync()
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
        const val ARG_SUBSCRIPTION_URI = "subscription_uri"
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
