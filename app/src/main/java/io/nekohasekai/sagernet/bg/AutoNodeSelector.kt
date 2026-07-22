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
import io.nekohasekai.sagernet.core.SubscriptionDataCore
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AutoNodeSelectionPhase {
    TESTING,
    TESTED,
    TEST_FAILED,
    CONFIRMING,
    SWITCHED,
    FAILED,
    MANUAL_HOLD,
}

internal data class AutoNodeSelectionStatus(
    val profileId: Long,
    val phase: AutoNodeSelectionPhase,
    val latencyMs: Int = 0,
    val until: Long = 0L,
)

/**
 * Owns the official libbox selector for one VPN session.
 *
 * A candidate must win two independent URL-test batches before it is selected. Selection is
 * make-before-break: new connections use the new outbound immediately, while the selector's
 * interrupt_exist_connections=false setting lets existing streams finish on the old outbound.
 */
internal class AutoNodeSelector(
    private val selectorTag: String,
    private val testGroupTag: String,
    private val profilesByTag: Map<String, ProxyEntity>,
    initialProfileId: Long,
    initiallyEnabled: Boolean,
    private val onMeasurements: suspend (Map<Long, Int>) -> Unit,
    private val onSelected: suspend (ProxyEntity) -> Unit,
    private val onStatus: suspend (AutoNodeSelectionStatus?) -> Unit,
    private val canSelect: suspend (ProxyEntity) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    companion object {
        const val NODE_TAG_PREFIX = "node-"
        const val FIRST_TEST_DELAY_MS = 30_000L
        const val TEST_INTERVAL_MS = 10 * 60 * 1000L
        const val CONFIRMATION_DELAY_MS = 3_000L
        const val SWITCH_COOLDOWN_MS = 20 * 60 * 1000L
        const val MANUAL_HOLD_MS = 30 * 60 * 1000L
        const val NETWORK_DEBOUNCE_MS = 10_000L
        private const val TEST_FIRST_RESULT_TIMEOUT_MS = 20_000L
        private const val TEST_QUIET_PERIOD_MS = 4_000L
        private const val TEST_MAX_DURATION_MS = 60_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        fun nodeTag(profileId: Long) = "$NODE_TAG_PREFIX$profileId"
    }

    private data class Measurement(val latencyMs: Int, val measuredAtSeconds: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val groupUpdates = Channel<Map<String, Measurement>>(Channel.CONFLATED)
    private val testRequests = Channel<Unit>(Channel.CONFLATED)
    private val stateLock = Any()
    private val selectionLock = Any()
    private val selectionCommitMutex = Mutex()
    private val measurementMutex = Mutex()
    private val manualMeasurementRunning = AtomicBoolean(false)
    private var currentTag = nodeTag(initialProfileId)
    private var stateRevision = 0L
    private var commandClient: CommandClient? = null
    private var clientGeneration = 0L
    private var schedulerJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkDebounceJob: Job? = null
    private var enableDelayJob: Job? = null
    private var statusClearJob: Job? = null
    private var cooldownUntil = 0L
    private var manualHoldUntil = 0L
    @Volatile private var connected = false
    @Volatile private var enabled = initiallyEnabled
    @Volatile private var closed = false

    fun start() {
        check(!closed) { "Automatic node selector is closed" }
        if (!openCommandClient()) scheduleReconnect()
        schedulerJob = scope.launch {
            delay(FIRST_TEST_DELAY_MS)
            while (isActive) {
                if (enabled) runCatching { measurementMutex.withLock { evaluate() } }
                    .onFailure {
                        Logs.w("Automatic node test failed", it)
                        if (enabled) publishFailure()
                    }
                withTimeoutOrNull(TEST_INTERVAL_MS) { testRequests.receive() }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        synchronized(stateLock) {
            stateRevision++
            if (!value) {
                manualHoldUntil = 0L
                cooldownUntil = 0L
            }
        }
        enabled = value
        enableDelayJob?.cancel()
        if (value) {
            enableDelayJob = scope.launch {
                delay(FIRST_TEST_DELAY_MS)
                testRequests.trySend(Unit)
            }
        } else {
            statusClearJob?.cancel()
            scope.launch { onStatus(null) }
        }
    }

    fun networkChanged() {
        if (!enabled || closed) return
        networkDebounceJob?.cancel()
        networkDebounceJob = scope.launch {
            delay(NETWORK_DEBOUNCE_MS)
            testRequests.trySend(Unit)
        }
    }

    /** Runs the in-service URL-test group without changing the selected outbound. */
    fun requestMeasurements(): Boolean {
        if (closed || !connected || profilesByTag.isEmpty()) return false
        if (!manualMeasurementRunning.compareAndSet(false, true)) return false
        scope.launch {
            try {
                measurementMutex.withLock { measureOnly() }
            } finally {
                manualMeasurementRunning.set(false)
            }
        }
        return true
    }

    private suspend fun measureOnly() {
        val selectedProfile = synchronized(stateLock) { profilesByTag[currentTag] } ?: return
        publishStatus(AutoNodeSelectionStatus(selectedProfile.id, AutoNodeSelectionPhase.TESTING))
        var results = measureFreshResults()
        if (results.isEmpty()) {
            delay(CONFIRMATION_DELAY_MS)
            results = measureFreshResults()
        }
        if (results.isEmpty()) {
            publishStatus(
                AutoNodeSelectionStatus(
                    selectedProfile.id,
                    AutoNodeSelectionPhase.TEST_FAILED,
                    until = now() + 4_000L,
                ),
                clearAfterMs = 4_000L,
            )
            return
        }
        onMeasurements(results)
        publishStatus(
            AutoNodeSelectionStatus(
                selectedProfile.id,
                AutoNodeSelectionPhase.TESTED,
                latencyMs = results.size,
                until = now() + 4_000L,
            ),
            clearAfterMs = 4_000L,
        )
    }

    /** Returns false when the running selector did not include this profile. */
    fun selectManually(currentProfile: ProxyEntity): Boolean {
        if (closed || !connected) return false
        val targetTag = nodeTag(currentProfile.id)
        val profile = profilesByTag[targetTag]?.takeIf {
            it.type == currentProfile.type && it.configRevision == currentProfile.configRevision
        } ?: return false
        val client = synchronized(stateLock) { commandClient } ?: return false
        return runCatching {
            val (selectionRevision, holdUntil) = synchronized(selectionLock) {
                client.selectOutbound(selectorTag, targetTag)
                synchronized(stateLock) {
                    currentTag = targetTag
                    stateRevision++
                    manualHoldUntil = if (enabled) now() + MANUAL_HOLD_MS else 0L
                    cooldownUntil = 0L
                    stateRevision to manualHoldUntil
                }
            }
            scope.launch {
                selectionCommitMutex.lock()
                try {
                    if (!selectionIsCurrent(targetTag, selectionRevision)) return@launch
                    onSelected(profile)
                    if (!selectionIsCurrent(targetTag, selectionRevision)) return@launch
                    if (holdUntil > now()) {
                        publishStatus(
                            AutoNodeSelectionStatus(
                                profileId = profile.id,
                                phase = AutoNodeSelectionPhase.MANUAL_HOLD,
                                until = holdUntil,
                            )
                        )
                    } else {
                        onStatus(null)
                    }
                } finally {
                    selectionCommitMutex.unlock()
                }
            }
            true
        }.getOrElse {
            Logs.w("In-place manual node selection failed", it)
            false
        }
    }

    internal suspend fun evaluate() {
        if (!enabled || !connected || profilesByTag.size < 2 || SagerNet.power.isPowerSaveMode) return
        val (selectedTag, evaluationRevision) = synchronized(stateLock) {
            currentTag to stateRevision
        }
        val selectedProfile = profilesByTag[selectedTag] ?: return
        val holdUntil = synchronized(stateLock) { manualHoldUntil }
        if (holdUntil > now()) {
            publishStatus(
                AutoNodeSelectionStatus(
                    selectedProfile.id,
                    AutoNodeSelectionPhase.MANUAL_HOLD,
                    until = holdUntil,
                )
            )
            return
        }

        publishStatus(AutoNodeSelectionStatus(selectedProfile.id, AutoNodeSelectionPhase.TESTING))
        var firstResults = measureFreshResults()
        if (firstResults.isEmpty()) {
            // Match the desktop policy: one unavailable batch gets a quick confirmation before
            // the selector reports failure or replaces a dead current node.
            delay(CONFIRMATION_DELAY_MS)
            if (!evaluationIsCurrent(selectedTag, evaluationRevision)) return
            firstResults = measureFreshResults()
            if (firstResults.isEmpty()) {
                publishFailure(selectedProfile.id)
                return
            }
        }
        onMeasurements(firstResults)
        if (!evaluationIsCurrent(selectedTag, evaluationRevision)) return
        val firstDecision = SubscriptionDataCore.selectMeaningfullyFaster(
            selectedProfile.id,
            firstResults,
        ) ?: run {
            onStatus(null)
            return
        }

        // During cooldown a healthy current node cannot be replaced. A missing/failed current
        // result still enters confirmation so a broken automatic choice can recover promptly.
        val inCooldown = synchronized(stateLock) { cooldownUntil > now() }
        if (inCooldown && firstDecision.currentLatencyMs != null) {
            onStatus(null)
            return
        }

        publishStatus(
            AutoNodeSelectionStatus(
                firstDecision.profileId,
                AutoNodeSelectionPhase.CONFIRMING,
                firstDecision.latencyMs,
            )
        )
        delay(CONFIRMATION_DELAY_MS)
        if (!evaluationIsCurrent(selectedTag, evaluationRevision)) return
        val confirmationResults = measureFreshResults()
        if (confirmationResults.isEmpty()) {
            publishFailure(selectedProfile.id)
            return
        }
        val stableResults = SubscriptionDataCore.stableAutoSwitchResults(
            selectedProfile.id,
            firstResults,
            confirmationResults,
        )
        onMeasurements(stableResults)
        if (!evaluationIsCurrent(selectedTag, evaluationRevision)) return
        val confirmed = SubscriptionDataCore.confirmAutoSwitch(
            firstDecision,
            selectedProfile.id,
            firstResults,
            confirmationResults,
        ) ?: run {
            onStatus(null)
            return
        }
        val targetTag = nodeTag(confirmed.profileId)
        val target = profilesByTag[targetTag] ?: return
        if (!canSelect(target)) {
            onStatus(null)
            return
        }
        val switchedRevision: Long? = synchronized(selectionLock) {
            if (!evaluationIsCurrent(selectedTag, evaluationRevision)) return@synchronized null
            val client = synchronized(stateLock) { commandClient } ?: return@synchronized null
            client.selectOutbound(selectorTag, targetTag)
            synchronized(stateLock) {
                currentTag = targetTag
                stateRevision++
                cooldownUntil = now() + SWITCH_COOLDOWN_MS
                stateRevision
            }
        }
        if (switchedRevision == null) return
        selectionCommitMutex.lock()
        try {
            if (!selectionIsCurrent(targetTag, switchedRevision)) return
            onSelected(target)
            if (!selectionIsCurrent(targetTag, switchedRevision)) return
            publishStatus(
                AutoNodeSelectionStatus(
                    target.id,
                    AutoNodeSelectionPhase.SWITCHED,
                    confirmed.latencyMs,
                    now() + 5_000L,
                ),
                clearAfterMs = 5_000L,
            )
        } finally {
            selectionCommitMutex.unlock()
        }
    }

    private fun evaluationIsCurrent(selectedTag: String, revision: Long): Boolean =
        enabled && synchronized(stateLock) {
            currentTag == selectedTag && stateRevision == revision && manualHoldUntil <= now()
        }

    private fun selectionIsCurrent(selectedTag: String, revision: Long): Boolean =
        synchronized(stateLock) { currentTag == selectedTag && stateRevision == revision }

    private suspend fun measureFreshResults(): Map<Long, Int> {
        while (groupUpdates.tryReceive().isSuccess) Unit
        val client = synchronized(stateLock) { commandClient } ?: return emptyMap()
        val startedAtSeconds = now() / 1_000L
        client.urlTest(testGroupTag)

        var latest = withTimeoutOrNull(TEST_FIRST_RESULT_TIMEOUT_MS) {
            groupUpdates.receive()
        } ?: return emptyMap()
        val deadline = now() + TEST_MAX_DURATION_MS
        while (now() < deadline) {
            val fresh = latest.values.count {
                it.latencyMs > 0 && it.measuredAtSeconds >= startedAtSeconds
            }
            if (fresh == profilesByTag.size) break
            val next = withTimeoutOrNull(TEST_QUIET_PERIOD_MS) { groupUpdates.receive() } ?: break
            latest = next
        }
        return latest.asSequence()
            .filter { (_, value) -> value.latencyMs > 0 && value.measuredAtSeconds >= startedAtSeconds }
            .mapNotNull { (tag, value) -> profilesByTag[tag]?.id?.let { it to value.latencyMs } }
            .toMap()
    }

    private suspend fun publishFailure(profileId: Long = synchronized(stateLock) {
        profilesByTag[currentTag]?.id ?: 0L
    }) {
        if (profileId <= 0L) return
        publishStatus(
            AutoNodeSelectionStatus(
                profileId,
                AutoNodeSelectionPhase.FAILED,
                until = now() + TEST_INTERVAL_MS,
            )
        )
    }

    private suspend fun publishStatus(
        status: AutoNodeSelectionStatus,
        clearAfterMs: Long = 0L,
    ) {
        statusClearJob?.cancel()
        onStatus(status)
        if (clearAfterMs > 0L) {
            statusClearJob = scope.launch {
                delay(clearAfterMs)
                onStatus(null)
            }
        }
    }

    private fun createHandler(generation: Long) = object : CommandClientHandler {
        override fun connected() {
            if (generation != synchronized(stateLock) { clientGeneration }) return
            connected = true
        }

        override fun disconnected(message: String) {
            if (generation != synchronized(stateLock) { clientGeneration } || closed) return
            connected = false
            Logs.w("Automatic node selector disconnected: $message")
            scheduleReconnect()
        }

        override fun writeGroups(message: OutboundGroupIterator) {
            if (generation != synchronized(stateLock) { clientGeneration }) return
            while (message.hasNext()) {
                val group = message.next()
                if (group.tag != testGroupTag) continue
                val measurements = linkedMapOf<String, Measurement>()
                val items = group.items
                while (items.hasNext()) {
                    val item = items.next()
                    if (item.tag in profilesByTag) {
                        measurements[item.tag] = Measurement(item.urlTestDelay, item.urlTestTime)
                    }
                }
                groupUpdates.trySend(measurements)
                return
            }
        }

        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents) = Unit
        override fun writeLogs(messageList: LogIterator) = Unit
        override fun writeOutbounds(messageList: OutboundGroupItemIterator) = Unit
        override fun writeStatus(message: StatusMessage) = Unit
    }

    private fun openCommandClient(): Boolean {
        if (closed) return false
        val generation = synchronized(stateLock) { ++clientGeneration }
        val client = CommandClient(createHandler(generation), CommandClientOptions().apply {
            statusInterval = 1_000L
            addCommand(Libbox.CommandGroup)
        })
        synchronized(stateLock) { commandClient = client }
        return runCatching {
            client.connect()
            true
        }.getOrElse {
            Logs.w("Unable to connect automatic node selector", it)
            synchronized(stateLock) {
                if (commandClient === client) commandClient = null
            }
            false
        }
    }

    private fun scheduleReconnect() {
        synchronized(stateLock) {
            if (closed || reconnectJob?.isActive == true) return
            reconnectJob = scope.launch {
                val stale = synchronized(stateLock) {
                    clientGeneration++
                    commandClient.also { commandClient = null }
                }
                runCatching { stale?.disconnect() }
                connected = false
                var attempt = 0
                while (isActive && !closed) {
                    delay(RECONNECT_DELAYS_MS[attempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)])
                    if (openCommandClient()) {
                        if (enabled) testRequests.trySend(Unit)
                        return@launch
                    }
                    attempt++
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        schedulerJob?.cancel()
        reconnectJob?.cancel()
        networkDebounceJob?.cancel()
        enableDelayJob?.cancel()
        statusClearJob?.cancel()
        val client = synchronized(stateLock) {
            clientGeneration++
            commandClient.also { commandClient = null }
        }
        runCatching { client?.disconnect() }
        groupUpdates.close()
        testRequests.close()
        scope.cancel()
    }
}
