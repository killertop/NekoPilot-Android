package io.nekohasekai.sagernet.bg

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.sagernet.core.AutoNodeSelectionPhase
import io.nekohasekai.sagernet.core.AutoNodeSelectionStatus
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
/**
 * Owns one sing-box selector and performs failure-triggered recovery only.
 *
 * Real traffic errors merely open a recovery attempt. Before changing nodes, two independent
 * current-path checks must also fail. There is no timer, latency write, or background speed test.
 */
internal class AutoNodeSelector(
    private val selectorTag: String,
    private val profilesByTag: Map<String, ProxyEntity>,
    initialProfileId: Long,
    initiallyEnabled: Boolean,
    initialNetworkIdentity: Long?,
    private val nextCandidate: suspend (Long, Set<Long>) -> ProxyEntity?,
    private val currentPathHealthy: suspend () -> Boolean,
    private val onSelected: suspend (ProxyEntity) -> Unit,
    private val onStatus: suspend (AutoNodeSelectionStatus?) -> Unit,
    private val canSelect: suspend (ProxyEntity) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val policy: NodeFailoverPolicy = NodeFailoverPolicy(),
) : AutoCloseable {

    companion object {
        const val NODE_TAG_PREFIX = "node-"
        private const val HEALTH_CONFIRMATION_DELAY_MS = 2_000L
        private const val RECOVERING_STATUS_MS = 15_000L
        private const val SWITCHED_STATUS_MS = 5_000L
        private const val FAILED_STATUS_MS = 15_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
        private val SELECTION_COMMIT_RETRY_DELAYS_MS = longArrayOf(0L, 100L, 300L, 1_000L, 3_000L)

        fun nodeTag(profileId: Long) = "$NODE_TAG_PREFIX$profileId"
    }

    private data class RecoveryRequest(val epoch: NodeFailureEpoch) {
        val tag: String get() = epoch.currentTag
    }

    private data class DetachedClient(val client: CommandClient?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryRequests = Channel<RecoveryRequest>(Channel.CONFLATED)
    private val stateLock = Any()
    private val selectionLock = Any()
    private val selectorOperationLock = Any()
    private val selectionCommitMutex = Mutex()
    private val recoveryMutex = Mutex()
    private val statusMutex = Mutex()
    private val statusSequence = AtomicLong()
    private var currentTag = nodeTag(initialProfileId)
    private var stateRevision = 0L
    private var networkGeneration = 0L
    private var commandClient: CommandClient? = null
    private var clientGeneration = 0L
    private var recoveryJob: Job? = null
    private var reconnectJob: Job? = null
    private var pendingReconnectGeneration: Long? = null
    private var statusClearJob: Job? = null
    private var selectionPersistenceJob: Job? = null
    private var connected = false
    private var enabled = initiallyEnabled
    private var networkIdentity = initialNetworkIdentity
    private var closed = false
    @Volatile
    private var reloadBlocked = false

    fun start() {
        check(!synchronized(selectionLock) { closed }) { "Automatic node switcher is closed" }
        recoveryJob = scope.launch {
            for (request in recoveryRequests) {
                if (!isActive) break
                try {
                    recoveryMutex.withLock { recoverCurrentPath(request) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                        Logs.w("Automatic node recovery failed", error)
                        val shouldPublish = synchronized(selectionLock) {
                            if (!requestIsCurrentLocked(request)) return@synchronized false
                            policy.markNoCandidate(request.epoch, emptySet(), now())
                        }
                        if (shouldPublish) publishFailure(request)
                }
            }
        }
        // The command client subscribes to libbox logs every second. It is only needed to detect
        // failures for automatic switching, so keep the default-disabled path free of a native
        // socket and recurring status work for the entire VPN session.
        if (synchronized(selectionLock) { enabled }) ensureCommandClientAsync()
    }

    fun setEnabled(value: Boolean) {
        val changed = synchronized(selectionLock) {
            if (closed || enabled == value) return@synchronized false
            enabled = value
            connected = false
            stateRevision++
            policy.resetForManualSelection()
            true
        }
        if (!changed) return
        clearStatusAsync()
        if (value) {
            ensureCommandClientAsync()
        } else {
            stopCommandClient()
        }
    }

    /**
     * Quiesces selector writes before a full VPN reload. The native selector operation and its
     * persistence retry must not race the candidate snapshot; a caller can safely hold the reload
     * transaction after this suspend function returns.
     */
    suspend fun blockForReload() {
        val persistence = synchronized(selectorOperationLock) {
            reloadBlocked = true
            synchronized(selectionLock) { selectionPersistenceJob }
        }
        persistence?.join()
        // Automatic recovery persistence is not kept in selectionPersistenceJob. Acquire the
        // same mutex after the tracked job finishes so an already-running write also quiesces.
        selectionCommitMutex.withLock { }
    }

    fun unblockAfterReload() {
        synchronized(selectorOperationLock) {
            reloadBlocked = false
        }
    }

    fun networkChanged(identity: Long?) {
        val changed = synchronized(selectionLock) {
            if (closed || networkIdentity == identity) return@synchronized false
            networkIdentity = identity
            stateRevision++
            networkGeneration++
            policy.resetForNetworkChange()
            true
        }
        if (changed) clearStatusAsync()
    }

    /** Additional monitors may report a current-outbound request failure here. */
    fun reportCurrentNodeFailure(message: String, expectedClientGeneration: Long? = null) {
        val request = synchronized(selectionLock) {
            if (
                expectedClientGeneration != null &&
                synchronized(stateLock) { clientGeneration != expectedClientGeneration }
            ) return@synchronized null
            if (!enabled || !connected || !networkAvailableLocked() || closed) {
                return@synchronized null
            }
            val tag = currentTag
            if (!isDefiniteCurrentNodeFailure(message, tag)) return@synchronized null
            val epoch = currentEpochLocked()
            if (
                policy.recordRequestFailure(
                    epoch,
                    now(),
                    physicalNetworkAvailable = networkAvailableLocked(),
                )
            ) {
                RecoveryRequest(epoch)
            } else {
                null
            }
        }
        request?.let(recoveryRequests::trySend)
    }

    /** Returns false when the immutable running selector does not contain this profile revision. */
    fun selectManually(currentProfile: ProxyEntity): Boolean {
        val targetTag = nodeTag(currentProfile.id)
        val profile = profilesByTag[targetTag]?.takeIf {
            it.type == currentProfile.type && it.configRevision == currentProfile.configRevision
        } ?: return false
        val selected = runCatching {
            synchronized(selectorOperationLock) operation@{
                if (synchronized(selectionLock) { closed || reloadBlocked }) return@operation false
                val activeClient = synchronized(selectionLock) {
                    if (enabled && connected) synchronized(stateLock) { commandClient } else null
                }
                if (activeClient != null) {
                    activeClient.selectOutbound(selectorTag, targetTag)
                } else {
                    // Automatic switching is disabled by default, so its log-monitoring command
                    // client is intentionally absent. Keep manual changes in-place by opening a
                    // one-shot client with no streaming commands, then closing it immediately.
                    selectOutboundOnce(targetTag)
                }
                synchronized(selectionLock) {
                    if (closed || reloadBlocked) return@operation false
                    currentTag = targetTag
                    stateRevision++
                    policy.resetForManualSelection()
                    true
                }
            }
        }.getOrElse { error ->
            Logs.w("In-place manual node selection failed", error)
            false
        }
        if (!selected) return false
        clearStatusAsync()
        val persistence = scope.launch {
            commitSelectionWithRetry(profile, targetTag)
        }
        synchronized(selectionLock) {
            selectionPersistenceJob?.cancel()
            selectionPersistenceJob = persistence
        }
        return true
    }

    private fun selectOutboundOnce(targetTag: String) {
        val client = CommandClient(oneShotCommandHandler(), CommandClientOptions())
        try {
            client.connect()
            client.selectOutbound(selectorTag, targetTag)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private suspend fun recoverCurrentPath(request: RecoveryRequest) {
        val failedProfile = synchronized(selectionLock) {
            if (!requestIsCurrentLocked(request) || profilesByTag.size < 2) {
                return@synchronized null
            }
            if (!policy.beginRecovery(request.epoch, now())) return@synchronized null
            profilesByTag[request.tag].also { if (it == null) policy.cancelRecovery() }
        } ?: return

        if (confirmCurrentPathHealthy(request)) {
            synchronized(selectionLock) {
                if (requestIsCurrentLocked(request)) policy.markPathHealthy(request.epoch)
            }
            clearStatusAsync()
            return
        }
        if (!requestIsCurrent(request)) {
            synchronized(selectionLock) { policy.cancelRecovery() }
            return
        }

        publishStatus(
            AutoNodeSelectionStatus(
                failedProfile.id,
                AutoNodeSelectionPhase.RECOVERING,
                until = now() + RECOVERING_STATUS_MS,
            ),
            clearAfterMs = RECOVERING_STATUS_MS,
            expectedTag = request.tag,
        )
        val failedIds = synchronized(selectionLock) {
            policy.excludedProfileIds(now()).toMutableSet().apply { add(failedProfile.id) }
        }

        while (
            requestIsCurrent(request) &&
            synchronized(selectionLock) { policy.isRecovering(request.epoch) }
        ) {
            val target = nextCandidate(failedProfile.id, failedIds)
            if (target == null) {
                synchronized(selectionLock) {
                    policy.markNoCandidate(request.epoch, failedIds, now())
                }
                publishFailure(request)
                return
            }
            val targetTag = nodeTag(target.id)
            val sessionTarget = profilesByTag[targetTag]
            if (
                sessionTarget == null || sessionTarget.type != target.type ||
                sessionTarget.configRevision != target.configRevision || !canSelect(target)
            ) {
                failedIds += target.id
                continue
            }

            val switched = runCatching {
                synchronized(selectorOperationLock) operation@{
                    val client = synchronized(selectionLock) {
                        if (
                            reloadBlocked || !requestIsCurrentLocked(request) ||
                            !policy.isRecovering(request.epoch)
                        ) {
                            return@operation false
                        }
                        synchronized(stateLock) { commandClient }
                    } ?: return@operation false
                    client.selectOutbound(selectorTag, targetTag)
                    synchronized(selectionLock) {
                        if (closed || reloadBlocked) return@operation false
                        currentTag = targetTag
                        stateRevision++
                        // A concurrent disable/network change may already have reset the policy,
                        // but the native selector operation still succeeded and must be persisted.
                        policy.markSwitched(request.epoch, failedIds, now())
                        true
                    }
                }
            }.getOrElse { error ->
                Logs.w("Automatic node switch failed", error)
                false
            }
            if (!switched) {
                synchronized(selectionLock) { policy.cancelRecovery() }
                clearStatusAsync()
                return
            }

            // Once libbox accepted a selector change, persist it even if the user disables
            // automatic switching immediately afterwards. This keeps UI state equal to the core.
            val committed = commitSelectionWithRetry(target, targetTag)
            val mayPublish = committed && synchronized(selectionLock) {
                !closed && enabled && connected && networkAvailableLocked() &&
                    currentTag == targetTag
            }
            if (mayPublish) {
                publishStatus(
                    AutoNodeSelectionStatus(
                        target.id,
                        AutoNodeSelectionPhase.SWITCHED,
                        target.ping,
                        now() + SWITCHED_STATUS_MS,
                    ),
                    clearAfterMs = SWITCHED_STATUS_MS,
                    expectedTag = targetTag,
                )
            } else {
                clearStatusAsync()
            }
            return
        }
        synchronized(selectionLock) { policy.cancelRecovery() }
        clearStatusAsync()
    }

    private suspend fun confirmCurrentPathHealthy(request: RecoveryRequest): Boolean {
        repeat(2) { attempt ->
            if (!requestIsCurrent(request)) return true
            if (runCatching { currentPathHealthy() }.getOrDefault(false)) return true
            if (attempt == 0) delay(HEALTH_CONFIRMATION_DELAY_MS)
        }
        return false
    }

    private suspend fun commitSelectionWithRetry(profile: ProxyEntity, targetTag: String): Boolean {
        var lastError: Throwable? = null
        for (retryDelay in SELECTION_COMMIT_RETRY_DELAYS_MS) {
            if (retryDelay > 0L) delay(retryDelay)
            if (!synchronized(selectionLock) { !closed && currentTag == targetTag }) return false
            try {
                val committed = selectionCommitMutex.withLock {
                    if (
                        reloadBlocked ||
                        !synchronized(selectionLock) { !closed && currentTag == targetTag }
                    ) {
                        return@withLock false
                    }
                    onSelected(profile)
                    true
                }
                if (committed) return true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        lastError?.let { Logs.w("Unable to persist selected node ${profile.id}", it) }
        scheduleSelectionPersistence(profile, targetTag)
        return false
    }

    private fun scheduleSelectionPersistence(profile: ProxyEntity, targetTag: String) {
        val job = scope.launch {
            var retryDelay = 5_000L
            while (isActive && synchronized(selectionLock) { !closed && currentTag == targetTag }) {
                delay(retryDelay)
                try {
                    val committed = selectionCommitMutex.withLock {
                        if (
                            reloadBlocked ||
                            !synchronized(selectionLock) { !closed && currentTag == targetTag }
                        ) {
                            return@withLock false
                        }
                        onSelected(profile)
                        true
                    }
                    if (committed) return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Logs.w("Retrying selected node persistence for ${profile.id}", error)
                }
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
        synchronized(selectionLock) {
            selectionPersistenceJob?.cancel()
            selectionPersistenceJob = job
            if (closed || currentTag != targetTag) job.cancel()
        }
    }

    private fun requestIsCurrent(request: RecoveryRequest): Boolean = synchronized(selectionLock) {
        requestIsCurrentLocked(request)
    }

    private fun requestIsCurrentLocked(request: RecoveryRequest): Boolean =
        !closed && enabled && connected && networkAvailableLocked() &&
            currentEpochLocked() == request.epoch

    private fun networkAvailableLocked() = networkIdentity != null

    private fun currentEpochLocked() = NodeFailureEpoch(
        currentTag = currentTag,
        stateRevision = stateRevision,
        networkGeneration = networkGeneration,
    )

    private suspend fun publishFailure(request: RecoveryRequest) {
        val profileId = profilesByTag[request.tag]?.id ?: return
        publishStatus(
            AutoNodeSelectionStatus(
                profileId,
                AutoNodeSelectionPhase.FAILED,
                until = now() + FAILED_STATUS_MS,
            ),
            clearAfterMs = FAILED_STATUS_MS,
            expectedTag = request.tag,
            requireRecovering = false,
        )
    }

    private suspend fun publishStatus(
        status: AutoNodeSelectionStatus,
        clearAfterMs: Long = 0L,
        expectedTag: String,
        requireRecovering: Boolean = status.phase == AutoNodeSelectionPhase.RECOVERING,
    ) {
        val sequence = statusSequence.incrementAndGet()
        statusClearJob?.cancel()
        statusMutex.withLock {
            if (sequence != statusSequence.get()) return@withLock
            val valid = synchronized(selectionLock) {
                !closed && enabled && connected && networkAvailableLocked() &&
                    currentTag == expectedTag &&
                    (!requireRecovering || policy.isRecovering(currentEpochLocked()))
            }
            deliverStatus(if (valid) status else null)
        }
        if (clearAfterMs > 0L && sequence == statusSequence.get()) {
            statusClearJob = scope.launch {
                delay(clearAfterMs)
                statusMutex.withLock {
                    if (sequence == statusSequence.get()) deliverStatus(null)
                }
            }
        }
    }

    private fun clearStatusAsync() {
        val sequence = statusSequence.incrementAndGet()
        statusClearJob?.cancel()
        statusClearJob = scope.launch {
            statusMutex.withLock {
                if (sequence == statusSequence.get()) deliverStatus(null)
            }
        }
    }

    private suspend fun deliverStatus(status: AutoNodeSelectionStatus?) {
        try {
            onStatus(status)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Status text is best-effort and must never change failover policy or core state.
            Logs.w("Unable to publish automatic node switching status", error)
        }
    }

    private fun createHandler(generation: Long) = object : CommandClientHandler {
        override fun connected() {
            val accepted = synchronized(selectionLock) {
                if (closed || generation != synchronized(stateLock) { clientGeneration }) {
                    return@synchronized false
                }
                connected = true
                stateRevision++
                policy.resetFailureEvidence()
                true
            }
            if (!accepted) return
        }

        override fun disconnected(message: String) {
            val accepted = synchronized(selectionLock) {
                if (generation != synchronized(stateLock) { clientGeneration }) {
                    return@synchronized false
                }
                connected = false
                stateRevision++
                policy.resetFailureEvidence()
                true
            }
            if (!accepted) return
            clearStatusAsync()
            if (!synchronized(selectionLock) { closed || !enabled }) {
                Logs.w("Automatic node switcher disconnected: $message")
                scheduleReconnect(generation)
            }
        }

        override fun writeLogs(messageList: LogIterator) {
            while (messageList.hasNext()) {
                reportCurrentNodeFailure(messageList.next().message, generation)
            }
        }

        override fun writeConnectionEvents(events: ConnectionEvents) = Unit
        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeGroups(message: OutboundGroupIterator) = Unit
        override fun writeOutbounds(messageList: OutboundGroupItemIterator) = Unit
        override fun writeStatus(message: StatusMessage) = Unit
    }

    private fun ensureCommandClientAsync() {
        scope.launch {
            if (openCommandClient()) return@launch
            val retryGeneration = synchronized(selectionLock) {
                if (closed || !enabled) return@synchronized null
                synchronized(stateLock) {
                    clientGeneration.takeIf { commandClient == null }
                }
            }
            retryGeneration?.let(::scheduleReconnect)
        }
    }

    private fun openCommandClient(): Boolean {
        synchronized(selectionLock) {
            if (closed || !enabled) return false
        }
        val generation: Long
        val client: CommandClient
        synchronized(stateLock) {
            if (commandClient != null) return true
            generation = ++clientGeneration
            client = CommandClient(createHandler(generation), CommandClientOptions().apply {
                statusInterval = 1_000_000_000L
                addCommand(Libbox.CommandLog)
            })
            commandClient = client
        }
        return runCatching {
            client.connect()
            val stillActive = synchronized(selectionLock) {
                !closed && enabled &&
                    synchronized(stateLock) { commandClient === client }
            }
            if (!stillActive) {
                detachCommandClient(client)
                runCatching { client.disconnect() }
            }
            stillActive
        }.getOrElse { error ->
            Logs.w("Unable to connect automatic node switcher", error)
            detachCommandClient(client)
            runCatching { client.disconnect() }
            false
        }
    }

    private fun detachCommandClient(client: CommandClient): Boolean = synchronized(selectionLock) {
        val detached = synchronized(stateLock) {
            if (commandClient !== client) return@synchronized false
            clientGeneration++
            commandClient = null
            true
        }
        if (detached) {
            connected = false
            stateRevision++
            policy.resetFailureEvidence()
        }
        detached
    }

    private fun scheduleReconnect(expectedGeneration: Long) {
        var jobToStart: Job? = null
        synchronized(selectionLock) {
            if (closed || !enabled) return
            synchronized(stateLock) {
                if (clientGeneration != expectedGeneration) return
                if (reconnectJob != null) {
                    pendingReconnectGeneration = expectedGeneration
                    return
                }
                lateinit var job: Job
                job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        reconnectLoop(expectedGeneration)
                    } finally {
                        val pending = synchronized(selectionLock) selection@{
                            synchronized(stateLock) state@{
                                if (reconnectJob !== job) return@state null
                                reconnectJob = null
                                pendingReconnectGeneration.also {
                                    pendingReconnectGeneration = null
                                }
                            }
                        }
                        pending?.let(::scheduleReconnect)
                    }
                }
                reconnectJob = job
                jobToStart = job
            }
        }
        jobToStart?.start()
    }

    private suspend fun reconnectLoop(expectedGeneration: Long) {
        val detached = synchronized(selectionLock) selection@{
            if (closed || !enabled) return@selection null
            val value = synchronized(stateLock) state@{
                if (clientGeneration != expectedGeneration) return@state null
                clientGeneration++
                DetachedClient(commandClient.also { commandClient = null })
            } ?: return@selection null
            connected = false
            stateRevision++
            policy.resetFailureEvidence()
            value
        } ?: return
        runCatching { detached.client?.disconnect() }
        var attempt = 0
        while (scope.isActive && synchronized(selectionLock) { !closed && enabled }) {
            delay(RECONNECT_DELAYS_MS[attempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)])
            if (openCommandClient()) return
            val stillOwned = synchronized(selectionLock) {
                !closed && enabled && synchronized(stateLock) { commandClient == null }
            }
            if (!stillOwned) return
            attempt++
        }
    }

    private fun stopCommandClient() {
        val client = synchronized(stateLock) {
            reconnectJob?.cancel()
            reconnectJob = null
            pendingReconnectGeneration = null
            clientGeneration++
            commandClient.also { commandClient = null }
        }
        runCatching { client?.disconnect() }
    }

    /** A one-shot manual selector must not subscribe to logs or status streams. */
    private fun oneShotCommandHandler() = object : CommandClientHandler {
        override fun connected() = Unit
        override fun disconnected(message: String) = Unit
        override fun writeLogs(messageList: LogIterator) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents) = Unit
        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeGroups(message: OutboundGroupIterator) = Unit
        override fun writeOutbounds(messageList: OutboundGroupItemIterator) = Unit
        override fun writeStatus(message: StatusMessage) = Unit
    }

    override fun close() {
        val shouldClose = synchronized(selectionLock) {
            if (closed) return@synchronized false
            closed = true
            enabled = false
            connected = false
            stateRevision++
            policy.resetForNetworkChange()
            true
        }
        if (!shouldClose) return
        statusSequence.incrementAndGet()
        recoveryJob?.cancel()
        reconnectJob?.cancel()
        statusClearJob?.cancel()
        selectionPersistenceJob?.cancel()
        stopCommandClient()
        recoveryRequests.close()
        scope.cancel()
    }
}
