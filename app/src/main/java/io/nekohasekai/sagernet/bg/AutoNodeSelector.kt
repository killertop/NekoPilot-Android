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
import kotlinx.coroutines.withTimeoutOrNull

internal enum class AutoNodeSelectionPhase {
    TESTING,
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
        const val CONFIRMATION_DELAY_MS = 5_000L
        const val SWITCH_COOLDOWN_MS = 30 * 60 * 1000L
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
                if (enabled) runCatching { evaluate() }
                    .onFailure {
                        Logs.w("Automatic node test failed", it)
                        if (enabled) publishFailure()
                    }
                withTimeoutOrNull(TEST_INTERVAL_MS) { testRequests.receive() }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        synchronized(stateLock) { stateRevision++ }
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

    /** Returns false when the running selector did not include this profile. */
    fun selectManually(currentProfile: ProxyEntity): Boolean {
        if (closed || !connected) return false
        val targetTag = nodeTag(currentProfile.id)
        val profile = profilesByTag[targetTag]?.takeIf {
            it.type == currentProfile.type && it.configRevision == currentProfile.configRevision
        } ?: return false
        val client = synchronized(stateLock) { commandClient } ?: return false
        return runCatching {
            synchronized(selectionLock) {
                client.selectOutbound(selectorTag, targetTag)
                synchronized(stateLock) {
                    currentTag = targetTag
                    stateRevision++
                    manualHoldUntil = now() + MANUAL_HOLD_MS
                    cooldownUntil = 0L
                }
            }
            scope.launch {
                onSelected(profile)
                publishStatus(
                    AutoNodeSelectionStatus(
                        profileId = profile.id,
                        phase = AutoNodeSelectionPhase.MANUAL_HOLD,
                        until = synchronized(stateLock) { manualHoldUntil },
                    )
                )
            }
            true
        }.getOrElse {
            Logs.w("In-place manual node selection failed", it)
            false
        }
    }

    internal suspend fun evaluate() {
        if (!enabled || !connected || profilesByTag.size < 2) return
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
        val firstResults = measureFreshResults()
        if (firstResults.isEmpty()) {
            publishFailure(selectedProfile.id)
            return
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
        onMeasurements(confirmationResults)
        if (!evaluationIsCurrent(selectedTag, evaluationRevision)) return
        val confirmed = SubscriptionDataCore.confirmAutoSwitch(
            firstDecision,
            selectedProfile.id,
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
        val switched = synchronized(selectionLock) {
            if (!evaluationIsCurrent(selectedTag, evaluationRevision)) return@synchronized false
            val client = synchronized(stateLock) { commandClient } ?: return@synchronized false
            client.selectOutbound(selectorTag, targetTag)
            synchronized(stateLock) {
                currentTag = targetTag
                stateRevision++
                cooldownUntil = now() + SWITCH_COOLDOWN_MS
            }
            true
        }
        if (!switched) return
        onSelected(target)
        publishStatus(
            AutoNodeSelectionStatus(
                target.id,
                AutoNodeSelectionPhase.SWITCHED,
                confirmed.latencyMs,
                now() + 5_000L,
            ),
            clearAfterMs = 5_000L,
        )
    }

    private fun evaluationIsCurrent(selectedTag: String, revision: Long): Boolean =
        enabled && synchronized(stateLock) {
            currentTag == selectedTag && stateRevision == revision && manualHoldUntil <= now()
        }

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
