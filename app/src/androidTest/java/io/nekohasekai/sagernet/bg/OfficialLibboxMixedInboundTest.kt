package io.nekohasekai.sagernet.bg

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.net.ConnectivityManager
import android.system.OsConstants
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KotlinSelectorNode
import io.nekohasekai.sagernet.fmt.KotlinNodeTestRoute
import io.nekohasekai.sagernet.fmt.KotlinSingBoxConfigInput
import io.nekohasekai.sagernet.fmt.buildKotlinNodeTestConfig
import io.nekohasekai.sagernet.fmt.buildKotlinSingBoxConfig
import io.nekohasekai.sagernet.fmt.parseProfiles
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Covers the same short-lived local mixed inbound used by node speed tests.
 * A direct outbound makes this a lifecycle/proxy test, independent of any subscription node.
 */
@RunWith(AndroidJUnit4::class)
class OfficialLibboxMixedInboundTest {
    @Test
    fun platformPublishesUsableDefaultInterface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(activePhysicalNetwork())
        val expectedName = requireNotNull(connectivity.getLinkProperties(network)?.interfaceName)
        val previousNetwork = SagerNet.underlyingNetwork
        SagerNet.underlyingNetwork = network
        val platform = OfficialLibboxPlatform(
            context = context,
            openTun = { error("TUN is not available in this test") },
            protectSocket = { true },
        )
        var publishedName = ""
        var publishedIndex = -1
        val listener = object : InterfaceUpdateListener {
            override fun updateDefaultInterface(
                interfaceName: String,
                interfaceIndex: Int,
                isExpensive: Boolean,
                isConstrained: Boolean,
            ) {
                publishedName = interfaceName
                publishedIndex = interfaceIndex
            }
        }
        try {
            // The product does not expose SSID/BSSID routing and therefore must not attempt a
            // permission-gated Wi-Fi identity read from a gomobile callback.
            assertEquals(null, platform.readWIFIState())
            platform.startDefaultInterfaceMonitor(listener)
            assertEquals(expectedName, publishedName)
            assertTrue(publishedIndex > 0)

            val interfaces = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
            platform.interfaces.let { iterator ->
                while (iterator.hasNext()) interfaces += iterator.next()
            }
            interfaces.forEach { networkInterface ->
                val addresses = networkInterface.addresses
                while (addresses.hasNext()) {
                    assertTrue("scoped IPv6 prefix leaked", '%' !in addresses.next())
                }
            }
            val upstream = requireNotNull(interfaces.firstOrNull { it.name == expectedName })
            assertTrue(upstream.flags and OsConstants.IFF_UP != 0)
            assertTrue(upstream.flags and OsConstants.IFF_RUNNING != 0)
        } finally {
            platform.closeDefaultInterfaceMonitor(listener)
            SagerNet.underlyingNetwork = previousNetwork
        }
    }

    @Test
    fun officialCoreAcceptsParallelNodeTestConfig() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        val first = SOCKSBean().apply {
            serverAddress = "127.0.0.1"
            serverPort = 10_801
        }
        val second = SOCKSBean().apply {
            serverAddress = "127.0.0.2"
            serverPort = 10_802
        }
        Libbox.checkConfig(buildKotlinNodeTestConfig(listOf(
            KotlinNodeTestRoute(first, "test-in-0", "test-node-0", 20_881),
            KotlinNodeTestRoute(second, "test-in-1", "test-node-1", 20_882),
        )))
    }

    @Test
    fun officialCoreAcceptsAutomaticSelectorConfig() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        val selected = SOCKSBean().apply {
            serverAddress = "127.0.0.1"
            serverPort = 1080
        }
        val candidate = SOCKSBean().apply {
            serverAddress = "127.0.0.2"
            serverPort = 1080
        }
        Libbox.checkConfig(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = selected,
                selectedProfileId = 11L,
                selectorNodes = listOf(
                    KotlinSelectorNode(11L, selected),
                    KotlinSelectorNode(22L, candidate),
                ),
                useVpn = false,
                forTest = true,
                ruleAssetDirectory = context.filesDir.absolutePath,
            ),
        ))
    }

    @Test
    fun officialCoreStartsGeneratedConfigWithLocalDnsPreference() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        val port = ServerSocket(0).use { it.localPort }
        val selected = SOCKSBean().apply {
            serverAddress = "127.0.0.1"
            serverPort = 1080
        }
        val controller = OfficialLibboxController(
            platform = OfficialLibboxPlatform(
                context = context,
                openTun = { error("TUN is not available in this test") },
                protectSocket = { true },
            ),
            onServiceStop = {},
            onServiceReload = {},
        )
        try {
            controller.startOrReload(buildKotlinSingBoxConfig(
                KotlinSingBoxConfigInput(
                    selected = selected,
                    useVpn = false,
                    forTest = true,
                    mixedPort = port,
                    ruleAssetDirectory = context.filesDir.absolutePath,
                ),
            ))
        } finally {
            controller.close()
        }
    }

    @Test
    fun officialCoreAcceptsRealVpnConfigWithBundledRuleSets() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        RuleAssetsUpdater.ensureBundledAssets(context)
        val ruleDirectory = context.getExternalFilesDir(null) ?: context.filesDir
        assertTrue(File(ruleDirectory, "geosite-cn.srs").isFile)
        assertTrue(File(ruleDirectory, "geoip-cn.srs").isFile)

        val selected = SOCKSBean().apply {
            serverAddress = "127.0.0.1"
            serverPort = 1080
        }
        Libbox.checkConfig(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = selected,
                useVpn = true,
                ruleAssetDirectory = ruleDirectory.absolutePath,
            ),
        ))
    }

    /**
     * Opt-in smoke test for a user-supplied node. The test link stays outside the repository and
     * is passed as the `nekopilot_test_node` instrumentation argument.
     */
    @Test
    fun suppliedNodeProxiesEgressWhenConfigured() {
        val nodeLink = InstrumentationRegistry.getArguments().getString("nekopilot_test_node")
        assumeTrue("No test node supplied", !nodeLink.isNullOrBlank())
        val node = parseProfiles(requireNotNull(nodeLink)).single()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        val port = ServerSocket(0).use { it.localPort }
        val controller = OfficialLibboxController(
            platform = OfficialLibboxPlatform(
                context = context,
                openTun = { error("TUN is not available in this test") },
                protectSocket = { true },
            ),
            onServiceStop = {},
            onServiceReload = {},
        )
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        try {
            controller.startOrReload(buildKotlinNodeTestConfig(listOf(
                KotlinNodeTestRoute(node, "provided-node-in", "provided-node", port),
            )))
            client.newCall(Request.Builder().url("https://www.example.com/").build()).execute().use { response ->
                assertTrue("unexpected HTTP ${response.code}", response.isSuccessful)
            }
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            controller.close()
        }
    }

    @Test
    fun servesRequestThroughFreshMixedInbound() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url("https://www.example.com/").build()).execute().use { response ->
                assertTrue("direct network unavailable: HTTP ${response.code}", response.isSuccessful)
            }
        val port = ServerSocket(0).use { it.localPort }
        val controller = OfficialLibboxController(
            platform = OfficialLibboxPlatform(
                context = context,
                openTun = { error("TUN is not available in this test") },
                protectSocket = { true },
            ),
            onServiceStop = {},
            onServiceReload = {},
        )
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            controller.startOrReload(
                JSONObject().apply {
                    put("log", JSONObject().put("level", "warn"))
                    put("inbounds", JSONArray().put(JSONObject().apply {
                        put("type", "mixed")
                        put("tag", "mixed-in")
                        put("listen", "127.0.0.1")
                        put("listen_port", port)
                    }))
                    put("outbounds", JSONArray().put(JSONObject().apply {
                        put("type", "direct")
                        put("tag", "direct")
                    }))
                    put("route", JSONObject().apply {
                        put("final", "direct")
                        put("default_domain_resolver", "dns-bootstrap")
                        put("rules", JSONArray().put(
                            JSONObject().put("ip_cidr", JSONArray().put("223.5.5.5/32")).put("action", "direct"),
                        ))
                    })
                    put("dns", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("type", "https")
                            put("tag", "dns-bootstrap")
                            put("server", "223.5.5.5")
                            put("path", "/dns-query")
                            put("tls", JSONObject().apply {
                                put("enabled", true)
                                put("server_name", "dns.alidns.com")
                            })
                        }))
                        put("final", "dns-bootstrap")
                    })
                }.toString(),
            )
            client.newCall(Request.Builder().url("https://www.example.com/").build()).execute().use { response ->
                assertTrue("unexpected HTTP ${response.code}", response.isSuccessful)
            }
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            controller.close()
        }
    }
}
