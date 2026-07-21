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

/**
 * Per-node network test backed by the same official libbox runtime as VPN service.
 * Each test owns a short-lived mixed inbound, avoiding legacy selector and private JNI APIs.
 */
class TestInstance(
    private val profile: ProxyEntity,
    private val link: String,
    private val timeout: Int,
    @Suppress("UNUSED_PARAMETER") testProfiles: List<ProxyEntity> = listOf(profile),
    private val downloadEnabled: Boolean = false,
    private val downloadLink: String = "https://speed.cloudflare.com/__down?bytes=1048576",
) {

    companion object {
        // libbox command-server startup is process-global. Serialize temporary test cores;
        // network requests remain bounded and the UI still updates after each node.
        private val testCoreMutex = Mutex()
    }

    suspend fun doTest(): UrlTestResult = testCoreMutex.withLock {
        TestSession().use { session ->
            session.runNodeTest(profile)
        }
    }

    suspend fun runBatch(
        targets: List<ProxyEntity>,
        onResult: (ProxyEntity, UrlTestResult) -> Unit,
        onError: (ProxyEntity, Throwable) -> Unit,
    ) = testCoreMutex.withLock {
        TestSession().use { session ->
            for (target in targets) {
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
     * A batch must stay serialized because libbox owns one process-global command server.
     * Reusing that command server and localhost proxy across profiles avoids a complete native
     * bootstrap/teardown for every node while preserving the exact same test configuration.
     */
    private inner class TestSession : AutoCloseable {
        private val port = ServerSocket(0).use { it.localPort }
        private var controller: OfficialLibboxController? = null
        private val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .callTimeout(Duration.ofMillis(timeout.toLong()))
            .build()

        init {
            OfficialLibboxRuntime.ensureSetup(SagerNet.application)
            Logs.d("Starting official libbox node test session")
        }

        fun runNodeTest(target: ProxyEntity): UrlTestResult {
            // A prior service is replaced by startOrReloadService. Evict old mixed-inbound
            // connections first so OkHttp never sends a request through the previous profile.
            client.connectionPool.evictAll()
            val config = buildKotlinSingBoxConfig(
                KotlinSingBoxConfigInput(
                    selected = target.requireBean(),
                    useVpn = false,
                    mixedPort = port,
                    ruleAssetDirectory = SagerNet.application.externalAssets.absolutePath,
                    forTest = true,
                ),
            )
            val activeController = controller ?: OfficialLibboxController(
                platform = OfficialLibboxPlatform(
                    SagerNet.application,
                    openTun = { error("TUN is not available in a node test") },
                    protectSocket = { true },
                ),
                onServiceStop = {},
                onServiceReload = {},
            ).also { controller = it }

            try {
                Logs.d("Starting official libbox node test for ${target.id}")
                activeController.startOrReload(config)
                Logs.d("Official libbox node test core started for ${target.id}")
            } catch (error: Throwable) {
                // startOrReloadService closes its controller after a native startup failure.
                // Drop that unusable instance so the next node can create a clean session.
                if (controller === activeController) {
                    controller = null
                    activeController.close()
                }
                throw error
            }

            val started = SystemClock.elapsedRealtime()
            client.newCall(
                Request.Builder()
                    .url(link)
                    .header("Connection", "close")
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
            controller?.close()
            controller = null
            Logs.d("Official libbox node test session stopped")
        }
    }
}
