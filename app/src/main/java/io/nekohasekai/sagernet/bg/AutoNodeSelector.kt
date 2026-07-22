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

/**
 * Uses the official libbox selector and connection stream to choose a faster node without
 * restarting Android's VPN service. A pending switch is committed only after traffic through
 * the old node has drained for [CONNECTION_DRAIN_MS]. The selector is also configured with
 * interrupt_exist_connections=false, so a callback race can never terminate an existing stream.
 */
internal class AutoNodeSelector(
    private val profilesByTag: Map<String, ProxyEntity>,
    initialProfileId: Long,
    private val onMeasurements: suspend (Map<Long, Int>) -> Unit,
    private val onSelected: suspend (ProxyEntity) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : CommandClientHandler, AutoCloseable {

    companion object {
        const val SELECTOR_TAG = "proxy"
        const val NODE_TAG_PREFIX = "node-"
        const val TEST_INTERVAL_MS = 10 * 60 * 1000L
        private const val TEST_FIRST_RESULT_TIMEOUT_MS = 20_000L
        private const val TEST_QUIET_PERIOD_MS = 4_000L
        private const val TEST_MAX_DURATION_MS = 60_000L
        private const val CONNECTION_DRAIN_MS = 2_000L

        fun nodeTag(profileId: Long) = "$NODE_TAG_PREFIX$profileId"
    }

    private data class Measurement(val latencyMs: Int, val measuredAtSeconds: Long)

    private data class ActiveConnection(val tags: Set<String>)

    private data class PendingSwitch(val fromTag: String, val toTag: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val groupUpdates = Channel<Map<String, Measurement>>(Channel.CONFLATED)
    private val stateLock = Any()
    private val activeConnections = linkedMapOf<String, ActiveConnection>()
    private var currentTag = nodeTag(initialProfileId)
    private var pendingSwitch: PendingSwitch? = null
    private var drainJob: Job? = null
    private var schedulerJob: Job? = null
    @Volatile private var connected = false
    @Volatile private var closed = false

    private val commandClient = CommandClient(this, CommandClientOptions().apply {
        statusInterval = 1_000L
        addCommand(Libbox.CommandGroup)
        addCommand(Libbox.CommandConnections)
    })

    fun start() {
        check(!closed) { "Automatic node selector is closed" }
        commandClient.connect()
        schedulerJob = scope.launch {
            while (isActive) {
                delay(TEST_INTERVAL_MS)
                runCatching { testAndQueueFasterNode() }
                    .onFailure { Logs.w("Automatic node test failed", it) }
            }
        }
    }

    internal suspend fun testAndQueueFasterNode() {
        if (!connected || profilesByTag.size < 2) return
        while (groupUpdates.tryReceive().isSuccess) Unit
        val startedAtSeconds = now() / 1_000L
        commandClient.urlTest(SELECTOR_TAG)

        var latest = withTimeoutOrNull(TEST_FIRST_RESULT_TIMEOUT_MS) {
            groupUpdates.receive()
        } ?: return
        val deadline = now() + TEST_MAX_DURATION_MS
        while (now() < deadline) {
            val next = withTimeoutOrNull(TEST_QUIET_PERIOD_MS) { groupUpdates.receive() } ?: break
            latest = next
            if (latest.size == profilesByTag.size && latest.values.all {
                    it.latencyMs > 0 && it.measuredAtSeconds >= startedAtSeconds
                }) break
        }

        val resultsByTag = latest.filterValues {
            it.latencyMs > 0 && it.measuredAtSeconds >= startedAtSeconds
        }.mapValues { it.value.latencyMs }
        val resultsById = resultsByTag.mapNotNull { (tag, latency) ->
            profilesByTag[tag]?.id?.let { it to latency }
        }.toMap()
        if (resultsById.isEmpty()) return
        onMeasurements(resultsById)

        val selectedTag = synchronized(stateLock) { currentTag }
        val selectedId = profilesByTag[selectedTag]?.id ?: return
        val decision = SubscriptionDataCore.selectMeaningfullyFaster(selectedId, resultsById)
            ?: return
        val targetTag = nodeTag(decision.profileId)
        if (targetTag !in profilesByTag) return
        synchronized(stateLock) {
            pendingSwitch = PendingSwitch(selectedTag, targetTag)
        }
        scheduleDrainCheck()
    }

    private fun scheduleDrainCheck() {
        val pending = synchronized(stateLock) { pendingSwitch } ?: return
        val hasActiveOldConnection = synchronized(stateLock) {
            SubscriptionDataCore.hasActiveConnectionsOnNode(
                pending.fromTag,
                activeConnections.values.map(ActiveConnection::tags),
            )
        }
        if (hasActiveOldConnection) {
            drainJob?.cancel()
            drainJob = null
            return
        }
        if (drainJob?.isActive == true) return
        drainJob = scope.launch {
            delay(CONNECTION_DRAIN_MS)
            val ready = synchronized(stateLock) {
                val currentPending = pendingSwitch
                currentPending == pending &&
                    !SubscriptionDataCore.hasActiveConnectionsOnNode(
                        pending.fromTag,
                        activeConnections.values.map(ActiveConnection::tags),
                    )
            }
            if (!ready) return@launch
            runCatching {
                commandClient.selectOutbound(SELECTOR_TAG, pending.toTag)
                val profile = requireNotNull(profilesByTag[pending.toTag])
                synchronized(stateLock) {
                    currentTag = pending.toTag
                    pendingSwitch = null
                }
                onSelected(profile)
            }.onFailure { Logs.w("Automatic node switch failed", it) }
        }
    }

    override fun writeGroups(message: OutboundGroupIterator) {
        while (message.hasNext()) {
            val group = message.next()
            if (group.tag != SELECTOR_TAG) continue
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

    override fun writeConnectionEvents(events: ConnectionEvents) {
        synchronized(stateLock) {
            if (events.reset) activeConnections.clear()
            val iterator = events.iterator()
            while (iterator.hasNext()) {
                val event = iterator.next()
                when (event.type.toLong()) {
                    Libbox.ConnectionEventNew -> event.connection?.let { connection ->
                        val tags = linkedSetOf(connection.fromOutbound, connection.outbound)
                            .filterTo(linkedSetOf()) { it.isNotBlank() }
                        val chain = connection.chain()
                        while (chain.hasNext()) tags += chain.next()
                        activeConnections[event.id] = ActiveConnection(tags)
                    }
                    Libbox.ConnectionEventClosed -> activeConnections.remove(event.id)
                }
            }
        }
        scheduleDrainCheck()
    }

    override fun connected() {
        connected = true
    }

    override fun disconnected(message: String) {
        connected = false
        Logs.w("Automatic node selector disconnected: $message")
    }

    override fun close() {
        if (closed) return
        closed = true
        schedulerJob?.cancel()
        drainJob?.cancel()
        runCatching { commandClient.disconnect() }
        groupUpdates.close()
        scope.cancel()
    }

    override fun clearLogs() = Unit
    override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
    override fun setDefaultLogLevel(level: Int) = Unit
    override fun updateClashMode(newMode: String) = Unit
    override fun writeLogs(messageList: LogIterator) = Unit
    override fun writeOutbounds(messageList: OutboundGroupItemIterator) = Unit
    override fun writeStatus(message: StatusMessage) = Unit
}
