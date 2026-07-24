package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ICorePreflightCallback
import io.nekohasekai.sagernet.aidl.ICorePreflightService
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

// A String crosses Binder as UTF-16 plus parcel overhead. Keep this well below the transaction
// ceiling and enforce the same limit in both processes.
private const val MAX_CANDIDATE_CONFIG_BYTES = 192 * 1024
private const val MAX_CANDIDATE_CONFIG_CHARS = 192 * 1024
private const val MAX_PROBE_URLS = 2
private const val MAX_PROBE_URL_CHARS = 2 * 1024
private const val MIN_PROBE_TIMEOUT_MS = 1_000
private const val MAX_PROBE_TIMEOUT_MS = 8_000
private const val CANDIDATE_PORT_CONFLICT_FAILURE = "Candidate core loopback port was claimed"

/** A safe, user-visible failure category: the running VPN was intentionally left untouched. */
internal class CandidateCorePreflightException(
    message: String,
    internal val retryablePortConflict: Boolean = false,
) : IllegalStateException(message)

private data class CandidateCorePreflightResult(
    val healthy: Boolean,
    val failure: String,
)

private fun validateCandidateRequest(config: String, probeUrls: Collection<String>) {
    require(config.length <= MAX_CANDIDATE_CONFIG_CHARS) {
        "Candidate core configuration exceeds the isolated-probe IPC limit"
    }
    require(config.toByteArray(Charsets.UTF_8).size <= MAX_CANDIDATE_CONFIG_BYTES) {
        "Candidate core configuration exceeds the isolated-probe IPC limit"
    }
    require(probeUrls.size in 1..MAX_PROBE_URLS) { "Invalid candidate health URL count" }
    require(probeUrls.all { it.isNotBlank() && it.length <= MAX_PROBE_URL_CHARS }) {
        "Invalid candidate health URL"
    }
}

private fun normalizedProbeTimeoutMillis(timeoutMillis: Int): Int =
    timeoutMillis.coerceIn(MIN_PROBE_TIMEOUT_MS, MAX_PROBE_TIMEOUT_MS)

/** Reject any config that could reconfigure the live VPN or publish a public listener. */
internal fun assertIsolatedPreflightConfig(config: String, port: Int) {
    val inbounds = requireNotNull(JSONObject(config).optJSONArray("inbounds")) {
        "A preflight core requires a mixed inbound"
    }
    check(inbounds.length() == 1) {
        "A preflight core may own exactly one temporary inbound"
    }
    val inbound = requireNotNull(inbounds.optJSONObject(0)) {
        "A preflight core inbound must be an object"
    }
    check(inbound.optString("type") == "mixed") {
        "A preflight core must use one mixed inbound and never a TUN"
    }
    check(inbound.optString("listen") == "127.0.0.1") {
        "A preflight core may listen only on loopback"
    }
    check(inbound.optInt("listen_port", -1) == port) {
        "Preflight mixed inbound port does not match its request"
    }
}

/**
 * Runs in a separate process and owns only a temporary localhost mixed inbound. It has a distinct
 * libbox state directory, never creates a TUN, and binds native egress sockets to the physical
 * network. That lets a reload validate the new proxy path while the live VPN remains authoritative.
 */
class CorePreflightService : Service() {
    companion object {
        private const val PREFLIGHT_DIRECTORY = "libbox-preflight"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeMutex = Mutex()
    private val runtimeDirectory by lazy { File(filesDir, PREFLIGHT_DIRECTORY) }
    @Volatile
    private var activeController: OfficialLibboxController? = null

    private val binder = object : ICorePreflightService.Stub() {
        override fun probe(
            config: String?,
            port: Int,
            username: String?,
            password: String?,
            probeUrls: Array<out String>?,
            timeoutMillis: Int,
            callback: ICorePreflightCallback?,
        ) {
            val target = callback ?: return
            scope.launch {
                val result = runCatching {
                    probeMutex.withLock {
                        runCandidate(
                            config = requireNotNull(config) { "Missing candidate config" },
                            port = port,
                            username = username.orEmpty(),
                            password = password.orEmpty(),
                            probeUrls = probeUrls?.toList().orEmpty(),
                            timeoutMillis = timeoutMillis,
                        )
                    }
                }.fold(
                    onSuccess = { CandidateCorePreflightResult(healthy = true, failure = "") },
                    onFailure = { error ->
                        if (error !is CancellationException) {
                            // Do not log the candidate config or native exception text: both can
                            // contain endpoint credentials. The caller only needs this safe class.
                            Logs.w("Candidate core preflight failed (${error.javaClass.simpleName})")
                        }
                        CandidateCorePreflightResult(
                            healthy = false,
                            failure = if (isAddressAlreadyInUse(error)) {
                                CANDIDATE_PORT_CONFLICT_FAILURE
                            } else {
                                "Candidate core did not pass its health check"
                            },
                        )
                    },
                )
                runCatching { target.complete(result.healthy, result.failure) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // The client unbinds on timeout. Cancelling a coroutine does not interrupt an in-flight
        // gomobile call, so close the native command server as well before a later probe waits on
        // [probeMutex].
        activeController?.requestClose()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        activeController?.requestClose()
        scope.cancel()
        super.onDestroy()
    }

    private fun runCandidate(
        config: String,
        port: Int,
        username: String,
        password: String,
        probeUrls: List<String>,
        timeoutMillis: Int,
    ) {
        require(port in 1..65_535) { "Invalid candidate proxy port" }
        validateCandidateRequest(config, probeUrls)
        assertIsolatedPreflightConfig(config, port)
        val previousNetwork = SagerNet.underlyingNetwork
        try {
            val physicalNetwork = activePhysicalNetwork()
                ?: error("No physical network available for candidate preflight")
            // This process does not own the live VPN's DefaultNetworkListener. Publish the verified
            // physical Network locally so both the platform monitor and native socket callbacks avoid
            // accidentally selecting the app VPN as their default interface.
            SagerNet.underlyingNetwork = physicalNetwork
            OfficialLibboxRuntime.ensureSetup(this, runtimeDirectory)
            Libbox.checkConfig(config)
            val controller = OfficialLibboxController(
                platform = OfficialLibboxPlatform(
                    context = this,
                    openTun = { error("A preflight core must not create a TUN") },
                    protectSocket = ::bindSocketToPhysicalNetwork,
                ),
                onServiceStop = {},
                onServiceReload = {},
            )
            activeController = controller
            try {
                controller.startOrReload(config)
                val probeTimeout = normalizedProbeTimeoutMillis(timeoutMillis)
                val healthy = probeUrls.asSequence()
                    .distinct()
                    .any { url ->
                        runCatching {
                            probeUrlThroughLocalMixedProxy(
                                url = url,
                                port = port,
                                username = username,
                                password = password,
                                timeoutMs = probeTimeout,
                            )
                        }.isSuccess
                    }
                check(healthy) { "Candidate core did not proxy a health request" }
            } finally {
                runCatching { controller.close() }
                if (activeController === controller) activeController = null
            }
        } finally {
            clearCandidateRuntimeArtifacts()
            SagerNet.underlyingNetwork = previousNetwork
        }
    }

    /** Never leave candidate credentials in the app's persistent libbox directory. */
    private fun clearCandidateRuntimeArtifacts() {
        runtimeDirectory.listFiles().orEmpty().forEach { child ->
            if (!child.deleteRecursively()) {
                Logs.w("Unable to clean candidate core artifact (${child.name})")
            }
        }
        if (!runtimeDirectory.isDirectory && !runtimeDirectory.mkdirs()) {
            Logs.w("Unable to recreate candidate core runtime directory")
            return
        }
        val tempDirectory = File(runtimeDirectory, "tmp")
        if (!tempDirectory.isDirectory && !tempDirectory.mkdirs()) {
            Logs.w("Unable to recreate candidate core temp directory")
        }
    }
}

/** Binds to [CorePreflightService], waits for one result, and always relinquishes the binding. */
internal object CorePreflightClient {
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val RESULT_OVERHEAD_MS = 2_000L

    suspend fun requireHealthy(
        context: Context,
        config: String,
        port: Int,
        username: String,
        password: String,
        probeUrls: List<String>,
        probeTimeoutMs: Int,
    ) {
        validateCandidateRequest(config, probeUrls)
        val connected = CompletableDeferred<ICorePreflightService>()
        val result = CompletableDeferred<CandidateCorePreflightResult>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val preflight = ICorePreflightService.Stub.asInterface(service)
                if (preflight == null) {
                    connected.completeExceptionally(
                        CandidateCorePreflightException("Candidate core process returned no service"),
                    )
                } else {
                    connected.complete(preflight)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                val failure = CandidateCorePreflightException("Candidate core process disconnected")
                if (!connected.isCompleted) connected.completeExceptionally(failure)
                result.complete(CandidateCorePreflightResult(healthy = false, failure = failure.message!!))
            }
        }
        val callback = object : ICorePreflightCallback.Stub() {
            override fun complete(healthy: Boolean, failure: String?) {
                result.complete(
                    CandidateCorePreflightResult(
                        healthy = healthy,
                        failure = failure.orEmpty().ifBlank { "Candidate core health check failed" },
                    ),
                )
            }
        }
        var bound = false
        try {
            bound = context.bindService(
                Intent(context, CorePreflightService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            check(bound) { "Unable to bind candidate core process" }
            val service = try {
                withTimeout(BIND_TIMEOUT_MS) { connected.await() }
            } catch (_: TimeoutCancellationException) {
                throw CandidateCorePreflightException("Candidate core process did not bind in time")
            }
            service.probe(
                config,
                port,
                username,
                password,
                probeUrls.toTypedArray(),
                probeTimeoutMs,
                callback,
            )
            val resultTimeout = BIND_TIMEOUT_MS + RESULT_OVERHEAD_MS +
                normalizedProbeTimeoutMillis(probeTimeoutMs).toLong() * probeUrls.size
            val outcome = try {
                withTimeout(resultTimeout) { result.await() }
            } catch (_: TimeoutCancellationException) {
                throw CandidateCorePreflightException("Candidate core health check timed out")
            }
            if (!outcome.healthy) {
                throw CandidateCorePreflightException(
                    outcome.failure,
                    retryablePortConflict = outcome.failure == CANDIDATE_PORT_CONFLICT_FAILURE,
                )
            }
        } catch (error: RemoteException) {
            throw CandidateCorePreflightException("Candidate core process was unavailable")
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
        }
    }
}
