package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sagernet.fmt.KotlinRouteRule
import io.nekohasekai.sagernet.fmt.KotlinSingBoxConfigInput
import io.nekohasekai.sagernet.fmt.buildKotlinSingBoxConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that a candidate health probe cannot be made healthy by a user direct rule. */
@RunWith(AndroidJUnit4::class)
class HealthProbeRoutingTest {
    @Test
    fun healthInboundCannotBeSatisfiedByUserDirectRoute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        LoopbackOrigin().use { origin ->
            val mixedPort = allocateEphemeralLoopbackPort(setOf(origin.port))
            val healthPort = allocateEphemeralLoopbackPort(setOf(origin.port, mixedPort))
            val unavailableSocksPort = allocateEphemeralLoopbackPort(
                setOf(origin.port, mixedPort, healthPort),
            )
            val config = buildKotlinSingBoxConfig(
                KotlinSingBoxConfigInput(
                    selected = SOCKSBean().apply {
                        serverAddress = "127.0.0.1"
                        serverPort = unavailableSocksPort
                    },
                    // The regular local proxy is allowed to use this user-authored direct rule.
                    // The private health inbound must ignore it and fail through the dead SOCKS
                    // outbound instead.
                    routeRules = listOf(
                        KotlinRouteRule(id = 1L, ip = "127.0.0.1/32", outbound = -1L),
                    ),
                    useVpn = false,
                    forTest = true,
                    mixedPort = mixedPort,
                    healthCheckPort = healthPort,
                    mixedUsername = "health-user",
                    mixedPassword = "health-password",
                    ruleAssetDirectory = context.filesDir.absolutePath,
                ),
            )
            Libbox.checkConfig(config)
            val controller = OfficialLibboxController(
                platform = OfficialLibboxPlatform(
                    context = context,
                    openTun = { error("TUN is not available in health-probe routing test") },
                    protectSocket = { true },
                ),
                onServiceStop = {},
                onServiceReload = {},
            )
            try {
                controller.startOrReload(config)
                val originUrl = "http://127.0.0.1:${origin.port}/"
                probeUrlThroughLocalMixedProxy(
                    url = originUrl,
                    port = mixedPort,
                    timeoutMs = 2_000,
                )
                assertTrue("normal inbound did not honor the user direct rule", origin.awaitRequest())

                val healthFailure = runCatching {
                    probeUrlThroughLocalMixedProxy(
                        url = originUrl,
                        port = healthPort,
                        username = "health-user",
                        password = "health-password",
                        timeoutMs = 2_000,
                    )
                }.exceptionOrNull()
                assertNotNull(
                    "health inbound bypassed the selected proxy through the user direct rule",
                    healthFailure,
                )
            } finally {
                controller.close()
            }
        }
    }

    private class LoopbackOrigin : Closeable {
        private val requests = CountDownLatch(1)
        private val server = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        val port: Int = server.localPort
        private val worker = thread(name = "health-probe-origin", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                socket.use { client ->
                    client.soTimeout = 2_000
                    val reader = client.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    client.getOutputStream().apply {
                        write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(StandardCharsets.US_ASCII))
                        flush()
                    }
                    requests.countDown()
                }
            }
        }

        fun awaitRequest(): Boolean = requests.await(2, TimeUnit.SECONDS)

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }
}
