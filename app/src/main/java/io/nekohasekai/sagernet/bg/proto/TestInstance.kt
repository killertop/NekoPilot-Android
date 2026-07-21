package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.OfficialLibboxController
import io.nekohasekai.sagernet.bg.OfficialLibboxPlatform
import io.nekohasekai.sagernet.bg.OfficialLibboxRuntime
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KotlinSingBoxConfigInput
import io.nekohasekai.sagernet.fmt.buildKotlinSingBoxConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.time.Duration

internal fun interface NodeTestSessionFactory {
    fun create(): NodeTestSession
}

internal interface NodeTestSession : AutoCloseable {
    fun runNodeTest(target: ProxyEntity): UrlTestResult
}

/**
 * Per-node network test backed by the same official libbox runtime as VPN service.
 * Each test owns a short-lived mixed inbound, avoiding legacy selector and private JNI APIs.
 */
internal class TestInstance(
    private val profile: ProxyEntity,
    private val link: String,
    private val timeout: Int,
    @Suppress("UNUSED_PARAMETER") testProfiles: List<ProxyEntity> = listOf(profile),
    private val downloadEnabled: Boolean = false,
    private val downloadLink: String = "https://speed.cloudflare.com/__down?bytes=1048576",
    private val sessionFactory: NodeTestSessionFactory? = null,
) {

    companion object {
        // libbox command-server startup is process-global. Serialize temporary test cores;
        // network requests remain bounded and the UI still updates after each node.
        private val testCoreMutex = Mutex()
    }

    suspend fun doTest(): UrlTestResult = testCoreMutex.withLock {
        createSession().use { session -> session.runNodeTest(profile) }
    }

    suspend fun runBatch(
        targets: List<ProxyEntity>,
        onResult: (ProxyEntity, UrlTestResult) -> Unit,
        onError: (ProxyEntity, Throwable) -> Unit,
    ) = testCoreMutex.withLock {
        for (target in targets) {
            // `startOrReloadService` tears down and recreates the mixed inbound. Reusing one
            // command server made the next node's HTTP request race that teardown and surface
            // as a misleading "connection reset" for otherwise healthy nodes. A fresh local
            // core per node is deliberately serialized, but keeps each result isolated.
            createSession().use { session ->
                try {
                    onResult(target, session.runNodeTest(target))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onError(target, error)
                }
            }
        }
    }

    /**
     * Each node owns a fresh command server and localhost proxy. libbox command-server reloads
     * replace the mixed inbound asynchronously, so cross-node reuse is not safe for URL tests.
     */
    private fun createSession(): NodeTestSession = sessionFactory?.create() ?: TestSession()

    private inner class TestSession : NodeTestSession {
        private val port = ServerSocket(0).use { it.localPort }
        private val controller: OfficialLibboxController
        private val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .callTimeout(Duration.ofMillis(timeout.toLong()))
            .build()

        init {
            OfficialLibboxRuntime.ensureSetup(SagerNet.application)
            controller = OfficialLibboxController(
                platform = OfficialLibboxPlatform(
                    SagerNet.application,
                    openTun = { error("TUN is not available in a node test") },
                    protectSocket = { true },
                ),
                onServiceStop = {},
                onServiceReload = {},
            )
        }

        override fun runNodeTest(target: ProxyEntity): UrlTestResult {
            val config = buildKotlinSingBoxConfig(
                KotlinSingBoxConfigInput(
                    selected = target.requireBean(),
                    useVpn = false,
                    mixedPort = port,
                    ruleAssetDirectory = SagerNet.application.externalAssets.absolutePath,
                    forTest = true,
                ),
            )
            Logs.d("Starting official libbox node test for ${target.id}")
            controller.startOrReload(config)
            Logs.d("Official libbox node test core started for ${target.id}")

            val started = SystemClock.elapsedRealtime()
            client.newCall(
                Request.Builder()
                    .url(link)
                    .build(),
            ).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
            }
            val latency = (SystemClock.elapsedRealtime() - started).toInt()
            val downloadMbps = if (downloadEnabled) {
                val downloadStarted = SystemClock.elapsedRealtime()
                var bytes = 0L
                client.newCall(Request.Builder().url(downloadLink).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    response.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (bytes < 1_048_576L) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            bytes += read
                        }
                    }
                }
                val elapsed = SystemClock.elapsedRealtime() - downloadStarted
                if (bytes > 0 && elapsed > 0) bytes * 0.008 / elapsed else null
            } else {
                null
            }
            return UrlTestResult(latency, downloadMbps)
        }

        override fun close() {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            controller.close()
        }
    }
}
