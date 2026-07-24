package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.fmt.KotlinSingBoxConfigInput
import io.nekohasekai.sagernet.fmt.buildKotlinSingBoxConfig
import io.nekohasekai.sagernet.fmt.parseProfiles
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the actual private :preflight process. The candidate is rejected before libbox starts,
 * then the already-running local core must still proxy a second request. This deliberately avoids
 * a TUN: it proves the no-touch failure boundary without needing VPN permission in a test APK.
 */
@RunWith(AndroidJUnit4::class)
class CorePreflightServiceTest {
    @Test
    fun rejectedCandidateDoesNotInterruptExistingMixedCore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        val origin = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val servedRequests = AtomicInteger()
        val served = CountDownLatch(2)
        val serverThread = Thread {
            runCatching {
                repeat(2) {
                    origin.accept().use(::respondOk)
                    servedRequests.incrementAndGet()
                    served.countDown()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val corePort = allocateEphemeralLoopbackPort(setOf(origin.localPort))
        val candidatePort = allocateEphemeralLoopbackPort(setOf(origin.localPort, corePort))
        val controller = OfficialLibboxController(
            platform = OfficialLibboxPlatform(
                context = context,
                openTun = { error("TUN is not available in this test") },
                protectSocket = { true },
            ),
            onServiceStop = {},
            onServiceReload = {},
        )
        val originUrl = "http://127.0.0.1:${origin.localPort}/"

        try {
            controller.startOrReload(directMixedConfig(corePort))
            probeUrlThroughLocalMixedProxy(originUrl, corePort, timeoutMs = 5_000)

            val invalidCandidate = JSONObject().put("inbounds", JSONArray()
                .put(JSONObject().put("type", "tun"))
                .put(
                    JSONObject()
                        .put("type", "mixed")
                        .put("listen", "127.0.0.1")
                        .put("listen_port", candidatePort),
                )).toString()
            val failure = runCatching {
                CorePreflightClient.requireHealthy(
                    context = context,
                    config = invalidCandidate,
                    port = candidatePort,
                    username = "",
                    password = "",
                    probeUrls = listOf(originUrl),
                    probeTimeoutMs = 1_000,
                )
            }.exceptionOrNull()

            assertTrue(failure is CandidateCorePreflightException)
            probeUrlThroughLocalMixedProxy(originUrl, corePort, timeoutMs = 5_000)
            assertTrue("old core requests were not both served", served.await(2, TimeUnit.SECONDS))
            assertEquals(2, servedRequests.get())
        } finally {
            controller.close()
            origin.close()
            serverThread.join(2_000)
        }
    }

    @Test
    fun unhealthyStartedCandidateIsCleanedUpWithoutInterruptingExistingCore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("A physical network is required for the isolated process", activePhysicalNetwork() != null)
        OfficialLibboxRuntime.ensureSetup(context)
        val origin = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val requestLines = Collections.synchronizedList(mutableListOf<String>())
        val served = CountDownLatch(2)
        val serverThread = Thread {
            runCatching {
                repeat(2) {
                    origin.accept().use { socket ->
                        val requestLine = readRequestLine(socket)
                        requestLines += requestLine
                        val status = if (requestLine.contains("/reject")) 503 else 204
                        writeResponse(socket, status)
                        served.countDown()
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val corePort = allocateEphemeralLoopbackPort(setOf(origin.localPort))
        val candidatePort = allocateEphemeralLoopbackPort(setOf(origin.localPort, corePort))
        val controller = OfficialLibboxController(
            platform = OfficialLibboxPlatform(
                context = context,
                openTun = { error("TUN is not available in this test") },
                protectSocket = { true },
            ),
            onServiceStop = {},
            onServiceReload = {},
        )
        val rejectedUrl = "http://127.0.0.1:${origin.localPort}/reject"
        val healthyUrl = "http://127.0.0.1:${origin.localPort}/healthy"

        try {
            controller.startOrReload(directMixedConfig(corePort))
            val failure = runCatching {
                CorePreflightClient.requireHealthy(
                    context = context,
                    config = directMixedConfig(candidatePort),
                    port = candidatePort,
                    username = "",
                    password = "",
                    probeUrls = listOf(rejectedUrl),
                    probeTimeoutMs = 1_000,
                )
            }.exceptionOrNull()

            assertTrue(failure is CandidateCorePreflightException)
            assertFalse(File(context.filesDir, "libbox-preflight/configuration.json").exists())
            probeUrlThroughLocalMixedProxy(healthyUrl, corePort, timeoutMs = 5_000)
            assertTrue("candidate and old core requests were not both served", served.await(5, TimeUnit.SECONDS))
            assertTrue(requestLines.any { it.contains("/reject") })
            assertTrue(requestLines.any { it.contains("/healthy") })
        } finally {
            controller.close()
            origin.close()
            serverThread.join(2_000)
        }
    }

    /** Opt-in real egress check; the node link is supplied only at test time, never stored. */
    @Test
    fun suppliedNodePassesIsolatedPreflightWhenConfigured() = runBlocking {
        val nodeLink = InstrumentationRegistry.getArguments().getString("nekopilot_test_node")
        assumeTrue("No test node supplied", !nodeLink.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("A physical network is required for the isolated process", activePhysicalNetwork() != null)
        val port = allocateEphemeralLoopbackPort()
        val config = buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = parseProfiles(requireNotNull(nodeLink)).single(),
                useVpn = false,
                mixedPort = port,
                mixedUsername = "preflight-test",
                mixedPassword = "preflight-test-password",
                ruleAssetDirectory = context.filesDir.absolutePath,
            ),
        )

        CorePreflightClient.requireHealthy(
            context = context,
            config = config,
            port = port,
            username = "preflight-test",
            password = "preflight-test-password",
            probeUrls = listOf("https://www.example.com/"),
            probeTimeoutMs = 8_000,
        )
        assertFalse(File(context.filesDir, "libbox-preflight/configuration.json").exists())
    }

    private fun respondOk(socket: Socket) {
        socket.soTimeout = 5_000
        readRequestLine(socket)
        writeResponse(socket, 200, "OK")
    }

    private fun readRequestLine(socket: Socket): String {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
        val requestLine = reader.readLine().orEmpty()
        while (reader.readLine()?.isNotEmpty() == true) Unit
        return requestLine
    }

    private fun writeResponse(socket: Socket, status: Int, body: String = "") {
        val reason = if (status in 200..299) "OK" else "Service Unavailable"
        val response = "HTTP/1.1 $status $reason\r\nContent-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n$body"
        socket.getOutputStream().apply {
            write(response.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }

    private fun directMixedConfig(port: Int): String = JSONObject().apply {
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
    }.toString()
}
