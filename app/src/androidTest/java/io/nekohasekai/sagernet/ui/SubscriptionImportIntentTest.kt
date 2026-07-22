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
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.group.GroupUpdater
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.assertEquals
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
        val originalState = waitForValue<OriginalState>(5_000) {
            if (!databasePath.exists()) return@waitForValue null
            runCatching {
                OriginalState(
                    groupIds = readGroupIds(databasePath.path),
                    selectedProxy = DataStore.selectedProxy,
                    selectedGroup = DataStore.selectedGroup,
                )
            }.getOrNull()
        }
        assertNotNull("Profile database was not initialized", originalState)
        val initialState = requireNotNull(originalState)

        val subscriptionUrl = decodeSubscriptionUrl(link!!)
        val existingGroup = findSubscriptionGroup(subscriptionUrl)
        var createdGroupId: Long? = null

        try {
            if (existingGroup == null) {
                // Reproduce the first-run path deterministically even when this test is executed on
                // a device that already contains unrelated profiles. The original selection is
                // restored in finally.
                runBlocking { DataStore.selectProxy(0L, DataStore.selectedGroup) }
            }

            launchMainActivity(Uri.parse(link))

            val confirmation = waitForValue(8_000, ::findSubscriptionConfirmation)
            assertNotNull("Subscription confirmation was not shown", confirmation)
            assertEquals(
                "Subscription confirmation did not match the stored source state",
                existingGroup != null,
                confirmation!!.updatesExisting,
            )
            val previousLastUpdated = existingGroup?.subscription?.lastUpdated ?: 0
            waitForNextUpdateSecond(previousLastUpdated)
            val updateRequestedAt = (System.currentTimeMillis() / 1_000L).toInt()
            assertTrue(
                "Subscription confirmation could not be accepted",
                confirmation.positiveButton.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            acceptUpdateWarningIfShown()

            val targetGroupId = existingGroup?.id ?: waitForValue(10_000) {
                findNewSubscriptionGroup(initialState.groupIds)
            }
            assertNotNull("Subscription group was not created or found", targetGroupId)
            if (existingGroup == null) createdGroupId = targetGroupId

            val updateFinished = waitForValue(30_000) {
                SagerDatabase.groupDao.getById(targetGroupId!!)
                    ?.subscription
                    ?.lastUpdated
                    ?.takeIf { updatedAt ->
                        updatedAt != previousLastUpdated &&
                            updatedAt >= updateRequestedAt &&
                            targetGroupId !in GroupUpdater.updating
                    }
            }
            assertNotNull(
                "Subscription update did not finish successfully. " +
                    "Visible UI: ${visibleTextSummary()}",
                updateFinished,
            )

            val profileCount = waitForValue(30_000) {
                SQLiteDatabase.openDatabase(
                    databasePath.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { database ->
                    database.rawQuery(
                        "SELECT COUNT(*) FROM proxy_entities WHERE groupId = ?",
                        arrayOf(targetGroupId.toString()),
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0).takeIf { it > 0 }
                    }
                }
            }
            assertNotNull("Subscription update did not add any profiles", profileCount)

            if (existingGroup == null) {
                val selectedProfileId = waitForValue(5_000) {
                    DataStore.configurationStore.refreshBlocking()
                    DataStore.selectedProxy.takeIf { selectedId ->
                        selectedId > 0L &&
                            SagerDatabase.proxyDao.getById(selectedId)?.groupId == targetGroupId
                    }
                }
                assertNotNull(
                    "The first imported subscription did not select a valid profile",
                    selectedProfileId,
                )
            }
        } finally {
            cleanupSubscriptionGroup(
                createdGroupId ?: if (existingGroup == null) {
                    runCatching { findSubscriptionGroup(subscriptionUrl)?.id }.getOrNull()
                } else null
            )
            runBlocking {
                DataStore.selectProxy(initialState.selectedProxy, initialState.selectedGroup)
            }
        }
    }

    @Test
    fun failedFirstRefreshRetainsImportedSourceForRetry() {
        launchMainActivity()

        val databasePath = context.getDatabasePath(Key.DB_PROFILE)
        val originalState = waitForValue<OriginalState>(5_000) {
            if (!databasePath.exists()) return@waitForValue null
            runCatching {
                OriginalState(
                    groupIds = readGroupIds(databasePath.path),
                    selectedProxy = DataStore.selectedProxy,
                    selectedGroup = DataStore.selectedGroup,
                )
            }.getOrNull()
        }
        assertNotNull("Profile database was not initialized", originalState)
        val initialState = requireNotNull(originalState)
        val sourceUrl = "https://127.0.0.1:1/nekopilot-unavailable-${SystemClock.elapsedRealtime()}"
        val importUri = Uri.Builder()
            .scheme("sn")
            .authority("subscription")
            .appendQueryParameter("url", sourceUrl)
            .appendQueryParameter("name", "Unavailable test source")
            .build()
        var createdGroupId: Long? = null

        try {
            launchMainActivity(importUri)
            val confirmation = waitForValue(8_000, ::findSubscriptionConfirmation)
            assertNotNull("Subscription confirmation was not shown", confirmation)
            assertTrue(
                "Subscription confirmation could not be accepted",
                confirmation!!.positiveButton.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )

            createdGroupId = waitForValue(10_000) {
                findSubscriptionGroup(sourceUrl)?.id
            }
            assertNotNull("Imported source was not created", createdGroupId)
            val targetGroupId = requireNotNull(createdGroupId)

            // localhost:1 fails without depending on an external provider. Observing the failure
            // UI proves the updater actually ran before checking that the source was retained.
            assertNotNull(
                "Imported source did not execute its first refresh",
                waitForValue(10_000, ::findSubscriptionFailureMessage),
            )
            val retained = waitForValue(10_000) {
                if (targetGroupId in GroupUpdater.updating) return@waitForValue null
                SagerDatabase.groupDao.getById(targetGroupId)
            }
            assertNotNull("Failed first refresh removed the imported source", retained)
            assertEquals(0L, SagerDatabase.proxyDao.countByGroup(targetGroupId))
            DataStore.configurationStore.refreshBlocking()
            assertEquals(targetGroupId, DataStore.selectedGroup)
        } finally {
            cleanupSubscriptionGroup(
                createdGroupId
                    ?: runCatching { findSubscriptionGroup(sourceUrl)?.id }.getOrNull()
            )
            runBlocking {
                DataStore.selectProxy(initialState.selectedProxy, initialState.selectedGroup)
            }
        }
    }

    private fun findSubscriptionConfirmation(): SubscriptionConfirmation? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val importTitle = context.getString(R.string.subscription_import)
        val existingTitle = context.getString(R.string.subscription_already_exists)
        val updatesExisting = when {
            root.containsExactText(importTitle) -> false
            root.containsExactText(existingTitle) -> true
            else -> return null
        }
        val positiveButton = root.findAccessibilityNodeInfosByViewId("android:id/button1")
            .firstOrNull() ?: return null
        return SubscriptionConfirmation(updatesExisting, positiveButton)
    }

    private fun visibleTextSummary(): String {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "<missing>"
        val texts = linkedSetOf<String>()
        fun collect(node: AccessibilityNodeInfo) {
            node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(texts::add)
            repeat(node.childCount) { index -> node.getChild(index)?.let(::collect) }
        }
        collect(root)
        return texts.joinToString(" | ").take(2_048).ifBlank { "<empty>" }
    }

    private fun findSubscriptionFailureMessage(): String? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return listOf(
            R.string.subscription_update_failed,
            R.string.subscription_update_timeout_error,
            R.string.subscription_update_dns_error,
            R.string.subscription_update_network_error,
            R.string.subscription_update_format_error,
        ).asSequence()
            .map(context::getString)
            .firstOrNull { message -> root.containsExactText(message) }
    }

    private fun decodeSubscriptionUrl(link: String): String {
        val uri = Uri.parse(link)
        uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val data = requireNotNull(uri.encodedQuery?.takeIf { it.isNotBlank() }) {
            "Subscription URI did not contain import data"
        }
        val group = KryoConverters.deserializeStrict(
            ProxyGroup().apply { export = true },
            Util.zlibDecompress(Util.b64Decode(data)),
        ).apply { export = false }
        return requireNotNull(group.subscription?.link?.trim()?.takeIf { it.isNotBlank() }) {
            "Subscription import data did not contain a source URL"
        }
    }

    private fun findSubscriptionGroup(subscriptionUrl: String): ProxyGroup? =
        SagerDatabase.groupDao.allGroups().firstOrNull { group ->
            group.type == GroupType.SUBSCRIPTION && sameSubscriptionUrl(
                group.subscription?.link.orEmpty(),
                subscriptionUrl,
            )
        }

    private fun readGroupIds(databasePath: String): Set<Long> = SQLiteDatabase.openDatabase(
        databasePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { database ->
        database.rawQuery("SELECT id FROM proxy_groups", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
    }

    private fun cleanupSubscriptionGroup(groupId: Long?) = runBlocking {
        groupId ?: return@runBlocking
        if (SagerDatabase.groupDao.getById(groupId)?.type == GroupType.SUBSCRIPTION) {
            GroupManager.deleteGroup(groupId)
        }
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
            if (!root.containsExactText(warning)) return@waitForValue null
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
        val arguments = mutableListOf(
            // Do not use `am start -W` while the target process is under instrumentation.
            // Android 15 can wait for the instrumented main thread indefinitely, preventing the
            // test from ever reaching its own UI-idle synchronization below.
            "am", "start", "--user", "current",
            "-n", component,
            "-f", if (data == null) "0x14000000" else "0x34000000",
        ).apply {
            if (data != null) addAll(listOf("-a", Intent.ACTION_VIEW, "-d", data.toString()))
        }
        require(arguments.none { argument -> argument.any(Char::isWhitespace) }) {
            "Activity launch arguments must be URI encoded"
        }
        // UiAutomation forwards this command to Runtime.exec rather than a shell. Quoting each
        // token would therefore try to execute a binary literally named `'am'` on Android 15.
        val command = arguments.joinToString(" ")
        val result = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        assertTrue(
            "MainActivity launch failed",
            result.contains("Starting: Intent") ||
                result.contains("Warning: Activity not started") ||
                result.contains("Status: ok"),
        )
        instrumentation.waitForIdleSync()
    }

    private fun AccessibilityNodeInfo.containsExactText(expected: String): Boolean =
        findAccessibilityNodeInfosByText(expected).any { it.text?.toString() == expected }

    private fun waitForNextUpdateSecond(previousLastUpdated: Int) {
        val currentSecond = (System.currentTimeMillis() / 1_000L).toInt()
        if (currentSecond != previousLastUpdated) return
        while ((System.currentTimeMillis() / 1_000L).toInt() == currentSecond) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
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

    private data class OriginalState(
        val groupIds: Set<Long>,
        val selectedProxy: Long,
        val selectedGroup: Long,
    )

    private data class SubscriptionConfirmation(
        val updatesExisting: Boolean,
        val positiveButton: AccessibilityNodeInfo,
    )
}
