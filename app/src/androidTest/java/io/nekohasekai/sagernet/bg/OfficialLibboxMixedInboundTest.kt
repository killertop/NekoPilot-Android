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
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KotlinSelectorNode
import io.nekohasekai.sagernet.fmt.KotlinNodeTestRoute
import io.nekohasekai.sagernet.fmt.KotlinRouteRule
import io.nekohasekai.sagernet.fmt.KotlinSingBoxConfigInput
import io.nekohasekai.sagernet.fmt.buildKotlinNodeTestConfig
import io.nekohasekai.sagernet.fmt.buildKotlinSingBoxConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.parseProfiles
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
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
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
            KotlinNodeTestRoute(
                first,
                "test-in-0",
                "test-node-0",
                20_881,
                "test-user-0",
                "test-password-0",
            ),
            KotlinNodeTestRoute(
                second,
                "test-in-1",
                "test-node-1",
                20_882,
                "test-user-1",
                "test-password-1",
            ),
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
                mixedUsername = "selector-user",
                mixedPassword = "selector-password",
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
                    mixedUsername = "controller-user",
                    mixedPassword = "controller-password",
                    ruleAssetDirectory = context.filesDir.absolutePath,
                ),
            ))
        } finally {
            controller.close()
        }
    }

    @Test
    fun officialCoreAcceptsVpnConfigWithEnabledAndDisabledChinaDefaults() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        RuleAssetsUpdater.ensureBundledAssets(context)
        val ruleDirectory = File(context.filesDir, "rule-assets")
        assertTrue(File(ruleDirectory, "geosite-cn.srs").isFile)
        assertTrue(File(ruleDirectory, "geoip-cn.srs").isFile)

        val selected = SOCKSBean().apply {
            serverAddress = "127.0.0.1"
            serverPort = 1080
        }
        val enabledChinaDefaults = listOf(
            KotlinRouteRule(id = 1L, domains = "rule_set:geosite-cn", outbound = -1L),
            KotlinRouteRule(id = 2L, ip = "rule_set:geoip-cn", outbound = -1L),
        )
        listOf(enabledChinaDefaults, emptyList()).forEach { routeRules ->
            Libbox.checkConfig(buildKotlinSingBoxConfig(
                KotlinSingBoxConfigInput(
                    selected = selected,
                    useVpn = true,
                    routeRules = routeRules,
                    healthCheckPort = 20_881,
                    mixedUsername = "health-user",
                    mixedPassword = "health-password",
                    ruleAssetDirectory = ruleDirectory.absolutePath,
                ),
            ))
        }
    }

    @Test
    fun officialCoreAcceptsSshConfigAndCompiledUserRules() {
        assertOfficialCoreAcceptsGeneratedConfig(
            SSHBean().apply {
                serverAddress = "ssh.example"
                serverPort = 22
                username = "user"
                authType = SSHBean.AUTH_TYPE_PASSWORD
                password = "password"
            },
            includeUserRule = true,
        )
    }

    @Test
    fun officialCoreAcceptsWireGuardEndpointConfig() {
        assertOfficialCoreAcceptsGeneratedConfig(WireGuardBean().apply {
            serverAddress = "wireguard.example"
            serverPort = 51_820
            localAddress = "10.0.0.2/32"
            privateKey = "K6ZFGQDIo1EPPoojWjum/bceCyqPDcPXLFJfdRnT+8g="
            peerPublicKey = "Ck1+A0XjAre3etA8bCxrKZ+agz/y2RO7CvbRNMo5tCE="
            reserved = "1,2,3"
        })
    }

    @Test
    fun officialCoreAcceptsShadowTlsConfig() {
        assertOfficialCoreAcceptsGeneratedConfig(ShadowTLSBean().apply {
            serverAddress = "shadowtls.example"
            serverPort = 443
            version = 3
            password = "password"
            sni = "edge.example"
        })
    }

    @Test
    fun officialCoreAcceptsNaiveConfig() {
        assertOfficialCoreAcceptsGeneratedConfig(NaiveBean().apply {
            serverAddress = "naive.example"
            serverPort = 443
            username = "user"
            password = "password"
            sni = "edge.example"
        })
    }

    @Test
    fun officialCoreAcceptsAnyTlsConfig() {
        assertOfficialCoreAcceptsGeneratedConfig(AnyTLSBean().apply {
            serverAddress = "anytls.example"
            serverPort = 443
            password = "password"
            sni = "edge.example"
            alpn = "h2,http/1.1"
            utlsFingerprint = "chrome"
        })
    }

    @Test
    fun officialCoreAcceptsCanonicalTuicV5Config() {
        assertOfficialCoreAcceptsGeneratedConfig(TuicBean().apply {
            protocolVersion = 5
            serverAddress = "tuic.example"
            serverPort = 443
            uuid = "2dd61d93-75d8-4da4-ac0e-6aece7eac365"
            token = "password"
            congestionController = "bbr"
            udpRelayMode = "quic"
            reduceRTT = true
        })
    }

    @Test
    fun officialCoreAcceptsCurrentHysteriaSchemas() {
        assertOfficialCoreAcceptsGeneratedConfig(HysteriaBean().apply {
            protocolVersion = 1
            serverAddress = "hysteria.example"
            serverPorts = "2000-2002,3000"
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = "password"
            uploadMbps = 10
            downloadMbps = 50
            hopInterval = 15
            streamReceiveWindow = 1_048_576
            connectionReceiveWindow = 2_097_152
            disableMtuDiscovery = true
        })
        assertOfficialCoreAcceptsGeneratedConfig(HysteriaBean().apply {
            protocolVersion = 2
            serverAddress = "hysteria2.example"
            serverPorts = "443"
            authPayload = "password"
        })
        assertOfficialCoreAcceptsGeneratedConfig(HysteriaBean().apply {
            protocolVersion = 2
            serverAddress = "hysteria2-gecko.example"
            serverPorts = "443"
            authPayload = "password"
            obfuscation = "mask"
            hysteria2ObfsType = HysteriaBean.HYSTERIA2_OBFS_GECKO
            hysteria2GeckoMinPacketSize = 64
            hysteria2GeckoMaxPacketSize = 512
        })
    }

    private fun assertOfficialCoreAcceptsGeneratedConfig(
        node: AbstractBean,
        includeUserRule: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        RuleAssetsUpdater.ensureBundledAssets(context)
        val ruleDirectory = File(context.filesDir, "rule-assets")
        val userRules = if (includeUserRule) listOf(KotlinRouteRule(
            id = 77L,
            domains = "full:api.example.com",
            outbound = -1L,
            userIds = listOf(10_001),
        )) else emptyList()
        Libbox.checkConfig(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = node,
                useVpn = true,
                routeRules = userRules,
                mixedUsername = "generated-user",
                mixedPassword = "generated-password",
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
        val username = "provided-node-test"
        val password = "provided-node-password"
        val client = OkHttpClient.Builder()
            .useLocalMixedProxy(
                enabled = true,
                port = port,
                username = username,
                password = password,
            )
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        try {
            controller.startOrReload(buildKotlinNodeTestConfig(listOf(
                KotlinNodeTestRoute(
                    node,
                    "provided-node-in",
                    "provided-node",
                    port,
                    username,
                    password,
                ),
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
        val origin = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val originFinished = CountDownLatch(1)
        val originFailure = AtomicReference<Throwable?>(null)
        val originThread = thread(name = "local-mixed-inbound-origin") {
            try {
                origin.accept().use { socket ->
                    socket.soTimeout = 5_000
                    check(socket.getInputStream().read() >= 0) { "mixed inbound did not forward a request" }
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(),
                    )
                    socket.getOutputStream().flush()
                }
            } catch (error: Throwable) {
                originFailure.set(error)
            } finally {
                originFinished.countDown()
            }
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
                    put("route", JSONObject().put("final", "direct"))
                }.toString(),
            )
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                socket.soTimeout = 5_000
                val request = (
                    "GET http://127.0.0.1:${origin.localPort}/health HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${origin.localPort}\r\nConnection: close\r\n\r\n"
                    ).toByteArray()
                socket.getOutputStream().write(request)
                socket.getOutputStream().flush()
                assertEquals("HTTP/1.1 200 OK", socket.getInputStream().bufferedReader().readLine())
            }
            assertTrue("local origin did not receive a request", originFinished.await(5, TimeUnit.SECONDS))
            originFailure.get()?.let { throw AssertionError("local origin failed", it) }
        } finally {
            controller.close()
            origin.close()
            originThread.join(5_000)
        }
    }
}
