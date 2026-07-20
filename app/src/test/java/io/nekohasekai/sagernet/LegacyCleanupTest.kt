package io.nekohasekai.sagernet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyCleanupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun removesLegacyDashboardDataAndRetiredPreferenceKeys() {
        val dataDir = temporaryFolder.newFolder("data")
        val root = dataDir.resolve("files").apply { mkdirs() }
        val yacd = root.resolve("yacd").apply { mkdirs() }
        yacd.resolve("index.html").writeText("old dashboard")
        root.resolve("yacd.zip").writeText("zip")
        root.resolve("yacd.version.txt").writeText("version")
        root.resolve("Yacd-meta-old").mkdirs()
        dataDir.resolve("app_webview").mkdirs()
        dataDir.resolve("app_webview_com.nekopilot.android:bg").mkdirs()
        dataDir.resolve("app_hws_webview").mkdirs()
        val keep = root.resolve("keep.txt").apply { writeText("keep") }
        val keepData = dataDir.resolve("databases").apply { mkdirs() }

        LegacyCleanup.removeClashDashboardData(root)

        assertFalse(yacd.exists())
        assertFalse(root.resolve("yacd.zip").exists())
        assertFalse(root.resolve("yacd.version.txt").exists())
        assertFalse(root.resolve("Yacd-meta-old").exists())
        assertFalse(dataDir.resolve("app_webview").exists())
        assertFalse(dataDir.resolve("app_webview_com.nekopilot.android:bg").exists())
        assertFalse(dataDir.resolve("app_hws_webview").exists())
        assertTrue(keep.exists())
        assertTrue(keepData.exists())
        assertEquals(
            listOf(
                "enableClashAPI",
                "clashApiSecret",
                "yacdURL",
                "rulesProvider",
                "serviceMode",
                "isAutoConnect",
                "bypassMode",
                "meteredNetwork",
                "alwaysShowAddress",
                "mtu",
                "globalCustomConfig",
                "acquireWakeLock",
                "bypassLan",
                "bypassLanInCore",
                "trafficSniffing",
                "resolveDestination",
                "ipv6Mode",
                "remoteDns",
                "directDns",
                "enableDnsRouting",
                "enableFakeDns",
                "domain_strategy_for_remote",
                "domain_strategy_for_direct",
                "domain_strategy_for_server",
                "connectionTestURL",
                "networkChangeResetConnections",
                "wakeResetConnections",
                "globalAllowInsecure",
                "allowInsecureOnRequest",
                "appTLSVersion",
                "showBottomBar",
            ),
            LegacyCleanup.removedPreferenceKeys,
        )
    }
}
