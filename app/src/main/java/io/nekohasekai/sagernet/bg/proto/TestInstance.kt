package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.DEFAULT_CONNECTION_TEST_CONCURRENCY
import io.nekohasekai.sagernet.MAX_CONNECTION_TEST_CONCURRENCY
import io.nekohasekai.sagernet.MIN_CONNECTION_TEST_CONCURRENCY
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.OfficialLibboxController
import io.nekohasekai.sagernet.bg.OfficialLibboxPlatform
import io.nekohasekai.sagernet.bg.OfficialLibboxRuntime
import io.nekohasekai.sagernet.bg.activePhysicalNetwork
import io.nekohasekai.sagernet.bg.probeUrlThroughLocalMixedProxy
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KotlinNodeTestRoute
import io.nekohasekai.sagernet.fmt.buildKotlinNodeTestConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

internal fun interface NodeTestSessionFactory {
    fun create(targets: List<ProxyEntity>): NodeTestSession
}

internal interface NodeTestSession : AutoCloseable {
    fun runNodeTest(target: ProxyEntity): UrlTestResult
}

private data class NodeTestSlot(
    val target: ProxyEntity,
    val port: Int,
    val inboundTag: String,
    val outboundTag: String,
)

/**
 * Per-node network test backed by the same official libbox runtime as VPN service.
 * Each test owns a short-lived mixed inbound, avoiding legacy selector and private JNI APIs.
 */
internal class TestInstance(
    private val profile: ProxyEntity,
    private val link: String,
    private val timeout: Int,
    private val downloadEnabled: Boolean = false,
    private val downloadLink: String = "https://speed.cloudflare.com/__down?bytes=1048576",
    private val concurrency: Int = DEFAULT_CONNECTION_TEST_CONCURRENCY,
    private val sessionFactory: NodeTestSessionFactory? = null,
    private val socketProtector: ((Int) -> Boolean)? = null,
) {

    companion object {
        private const val MAX_NODES_PER_TEST_CORE = 32

        // libbox command-server startup is process-global. Serialize temporary test cores while
        // each core runs its independent per-node local inbounds with bounded concurrency.
        private val testCoreMutex = Mutex()
    }

    suspend fun doTest(): UrlTestResult = testCoreMutex.withLock {
        createSession(listOf(profile)).use { session -> session.runNodeTest(profile) }
    }

    suspend fun runBatch(
        targets: List<ProxyEntity>,
        onResult: (ProxyEntity, UrlTestResult) -> Unit,
        onError: (ProxyEntity, Throwable) -> Unit,
    ) = testCoreMutex.withLock {
        if (targets.isEmpty()) return@withLock
        val reportedIds = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<Long, Boolean>(),
        )
        val reportResult: (ProxyEntity, UrlTestResult) -> Unit = { target, result ->
            if (reportedIds.add(target.id)) onResult(target, result)
        }
        val reportError: (ProxyEntity, Throwable) -> Unit = { target, error ->
            if (reportedIds.add(target.id)) onError(target, error)
        }
        // A subscription may contain thousands of nodes. Giving every node an inbound in one
        // libbox config would allocate thousands of listeners, retain a very large JSON graph,
        // and eventually exhaust Android's ephemeral ports. Keep the configured request
        // throughput, but bound each short-lived core and continue after closing it.
        try {
            for (batch in targets.chunked(MAX_NODES_PER_TEST_CORE)) {
                runBatchChunk(batch, reportResult, reportError)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A native/core-level failure between chunks must not leave untouched nodes showing
            // a stale successful latency from a previous run.
            targets.forEach { target -> reportError(target, error) }
        }
    }

    private suspend fun runBatchChunk(
        targets: List<ProxyEntity>,
        onResult: (ProxyEntity, UrlTestResult) -> Unit,
        onError: (ProxyEntity, Throwable) -> Unit,
    ) {
        val session = try {
            createSession(targets)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (targets.size == 1) {
                onError(targets.single(), error)
                return
            }
            // A malformed or temporarily unbindable outbound must not leave every later node
            // untested. Bisect only failed startup batches; normal request failures stay isolated
            // inside the shared core above.
            val midpoint = targets.size / 2
            runBatchChunk(targets.subList(0, midpoint), onResult, onError)
            runBatchChunk(targets.subList(midpoint, targets.size), onResult, onError)
            return
        }
        session.use {
            val workerCount = concurrency
                .coerceIn(MIN_CONNECTION_TEST_CONCURRENCY, MAX_CONNECTION_TEST_CONCURRENCY)
                .coerceAtMost(targets.size)
            val permits = Semaphore(workerCount)
            coroutineScope {
                targets.map { target ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
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
                .awaitAll()
            }
        }
    }

    private fun createSession(targets: List<ProxyEntity>): NodeTestSession =
        sessionFactory?.create(targets) ?: TestSession(targets)

    private inner class TestSession(targets: List<ProxyEntity>) : NodeTestSession {
        private val slots = run {
            val usedPorts = hashSetOf<Int>()
            targets.distinctBy(ProxyEntity::id).mapIndexed { index, target ->
                var port: Int
                do {
                    port = ServerSocket(0).use { it.localPort }
                } while (!usedPorts.add(port))
                NodeTestSlot(
                    target = target,
                    port = port,
                    inboundTag = "test-in-$index",
                    outboundTag = "test-node-$index",
                )
            }.also { require(it.size == targets.size) { "Duplicate node ids in a test batch" } }
        }
        private val slotsById = slots.associateBy { it.target.id }
        private val controller: OfficialLibboxController
        private val baseClient = OkHttpClient.Builder()
            .callTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .build()
        private val clientsById = slots.associate { slot ->
            slot.target.id to baseClient.newBuilder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", slot.port)))
                .build()
        }

        init {
            OfficialLibboxRuntime.ensureSetup(SagerNet.application)
            controller = OfficialLibboxController(
                platform = OfficialLibboxPlatform(
                    SagerNet.application,
                    openTun = { error("TUN is not available in a node test") },
                    protectSocket = socketProtector ?: ::bindTestSocketToPhysicalNetwork,
                ),
                onServiceStop = {},
                onServiceReload = {},
            )
            try {
                val config = buildKotlinNodeTestConfig(slots.map { slot ->
                    KotlinNodeTestRoute(
                        bean = slot.target.requireBean(),
                        inboundTag = slot.inboundTag,
                        outboundTag = slot.outboundTag,
                        mixedPort = slot.port,
                    )
                })
                Logs.d("Starting one official libbox node test core for ${slots.size} nodes")
                controller.startOrReload(config)
                Logs.d("Official libbox batch node test core started")
            } catch (error: Throwable) {
                runCatching { controller.close() }
                throw error
            }
        }

        override fun runNodeTest(target: ProxyEntity): UrlTestResult {
            val client = clientsById[target.id] ?: error("Node is not part of this test batch")
            val slot = slotsById[target.id] ?: error("Node route is not part of this test batch")

            val started = SystemClock.elapsedRealtime()
            probeUrlThroughLocalMixedProxy(
                url = link,
                port = slot.port,
                timeoutMs = timeout,
                httpsClient = client,
            )
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
            baseClient.dispatcher.cancelAll()
            baseClient.connectionPool.evictAll()
            controller.close()
        }
    }

    /**
     * A temporary test core lives in the UI process. While Android VPN is connected its native
     * sockets would otherwise be captured by NekoPilot's own TUN and measure a double-proxy path.
     * Bind every libbox outbound socket to Android's real upstream network instead.
     */
    private fun bindTestSocketToPhysicalNetwork(fd: Int): Boolean {
        val network = activePhysicalNetwork() ?: return false
        return runCatching {
            // fromFd duplicates the descriptor. Network.bindSocket applies to the underlying
            // socket, while closing this duplicate leaves libbox's original descriptor owned by Go.
            ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                network.bindSocket(duplicate.fileDescriptor)
            }
            true
        }.getOrElse { error ->
            Logs.w("Unable to bind node-test socket to the physical network", error)
            false
        }
    }
}
