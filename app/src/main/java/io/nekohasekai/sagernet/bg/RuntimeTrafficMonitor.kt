package io.nekohasekai.sagernet.bg

import android.os.Bundle
import android.os.SystemClock
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
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class RuntimeTrafficSnapshot(
    val available: Boolean,
    val profileId: Long,
    val uplinkBytesPerSecond: Long,
    val downlinkBytesPerSecond: Long,
    val sampledAtElapsedRealtime: Long,
) {
    fun isFresh(nowElapsedRealtime: Long, maxAgeMs: Long = MAX_SAMPLE_AGE_MS): Boolean =
        available && sampledAtElapsedRealtime > 0L &&
            nowElapsedRealtime - sampledAtElapsedRealtime in 0..maxAgeMs

    fun toBundle() = Bundle(5).apply {
        putBoolean(KEY_AVAILABLE, available)
        putLong(KEY_PROFILE_ID, profileId)
        putLong(KEY_UPLINK, uplinkBytesPerSecond)
        putLong(KEY_DOWNLINK, downlinkBytesPerSecond)
        putLong(KEY_SAMPLED_AT, sampledAtElapsedRealtime)
    }

    companion object {
        const val MAX_SAMPLE_AGE_MS = 2_500L
        private const val KEY_AVAILABLE = "available"
        private const val KEY_PROFILE_ID = "profileId"
        private const val KEY_UPLINK = "uplink"
        private const val KEY_DOWNLINK = "downlink"
        private const val KEY_SAMPLED_AT = "sampledAt"

        fun fromBundle(bundle: Bundle): RuntimeTrafficSnapshot = RuntimeTrafficSnapshot(
            available = bundle.getBoolean(KEY_AVAILABLE),
            profileId = bundle.getLong(KEY_PROFILE_ID),
            uplinkBytesPerSecond = bundle.getLong(KEY_UPLINK).coerceAtLeast(0L),
            downlinkBytesPerSecond = bundle.getLong(KEY_DOWNLINK).coerceAtLeast(0L),
            sampledAtElapsedRealtime = bundle.getLong(KEY_SAMPLED_AT),
        )

        fun unavailable(nowElapsedRealtime: Long = 0L) = RuntimeTrafficSnapshot(
            available = false,
            profileId = 0L,
            uplinkBytesPerSecond = 0L,
            downlinkBytesPerSecond = 0L,
            sampledAtElapsedRealtime = nowElapsedRealtime,
        )
    }
}

/**
 * Lazily subscribes to official libbox connection deltas while Home is actively polling.
 *
 * Binder reads only touch atomics and schedule work on [Dispatchers.IO]. When Home leaves the
 * STARTED lifecycle, polling stops and this monitor disconnects after [IDLE_TIMEOUT_MS].
 */
internal class RuntimeTrafficMonitor(
    private val sessionProxyTag: String,
    private val currentProfileId: () -> Long,
    private val nowElapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val COMMAND_INTERVAL_NS = 1_000_000_000L
        const val IDLE_TIMEOUT_MS = 3_000L
        val RECONNECT_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 5_000L)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val lastRequestedAt = AtomicLong(-1L)
    private val sampleWindowStartedAt = AtomicLong(0L)
    private val latest = AtomicReference(RuntimeTrafficSnapshot.unavailable())
    private val smoother = TrafficRateSmoother()
    private val accumulator = NodeTrafficAccumulator()
    @Volatile
    private var smoothedProfileId = 0L
    private var subscriptionJob: Job? = null
    private var idleJob: Job? = null
    private var commandClient: CommandClient? = null
    private var generation = 0L
    private var connectedGeneration = -1L
    private var closed = false

    /** Returns immediately; native subscription setup always happens on the monitor IO scope. */
    fun snapshot(): RuntimeTrafficSnapshot {
        val now = nowElapsedRealtime()
        lastRequestedAt.set(now)
        ensureSubscribed()
        ensureIdleWatcher()
        return latest.get().takeIf { it.isFresh(now) }
            ?: RuntimeTrafficSnapshot.unavailable(now)
    }

    private fun ensureSubscribed() {
        synchronized(stateLock) {
            if (closed || subscriptionJob?.isActive == true) return
            subscriptionJob = scope.launch { subscriptionLoop() }
        }
    }

    private fun ensureIdleWatcher() {
        synchronized(stateLock) {
            if (closed || idleJob?.isActive == true) return
            idleJob = scope.launch {
                while (isActive) {
                    delay(SAMPLE_INTERVAL_MS)
                    if (!isRecentlyRequested()) {
                        stopSubscription()
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun subscriptionLoop() {
        var attempt = 0
        while (scope.isActive && isRecentlyRequested()) {
            val disconnected = CompletableDeferred<Unit>()
            val currentGeneration = synchronized(stateLock) {
                if (closed) return
                ++generation
            }
            val client = CommandClient(
                createHandler(currentGeneration, disconnected),
                CommandClientOptions().apply {
                    // libbox expects a Go time.Duration expressed in nanoseconds.
                    statusInterval = COMMAND_INTERVAL_NS
                    addCommand(Libbox.CommandConnections)
                },
            )
            val accepted = synchronized(stateLock) {
                if (closed || generation != currentGeneration) return@synchronized false
                commandClient = client
                true
            }
            if (!accepted) {
                runCatching { client.disconnect() }
                return
            }
            var sampler: Job? = null
            try {
                client.connect()
                attempt = 0
                sampler = scope.launch {
                    while (isActive && !disconnected.isCompleted) {
                        delay(SAMPLE_INTERVAL_MS)
                        publishSample(currentGeneration)
                    }
                }
                disconnected.await()
            } catch (error: Throwable) {
                if (scope.isActive && isRecentlyRequested()) {
                    Logs.w("Runtime traffic monitor disconnected", error)
                }
            } finally {
                sampler?.cancel()
                synchronized(stateLock) {
                    if (commandClient === client) commandClient = null
                    if (generation == currentGeneration) {
                        connectedGeneration = -1L
                        resetTrafficStateLocked()
                    }
                }
                runCatching { client.disconnect() }
            }
            if (scope.isActive && isRecentlyRequested()) {
                delay(RECONNECT_DELAYS_MS[attempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)])
                attempt++
            }
        }
    }

    private fun createHandler(
        clientGeneration: Long,
        disconnected: CompletableDeferred<Unit>,
    ) = object : CommandClientHandler {
        override fun connected() {
            synchronized(stateLock) {
                if (closed || clientGeneration != generation) return
                connectedGeneration = clientGeneration
                resetTrafficStateLocked()
            }
        }

        override fun disconnected(message: String) {
            val accepted = synchronized(stateLock) {
                if (clientGeneration != generation) return@synchronized false
                connectedGeneration = -1L
                resetTrafficStateLocked()
                true
            }
            if (accepted) disconnected.complete(Unit)
        }

        override fun writeConnectionEvents(events: ConnectionEvents) {
            synchronized(stateLock) {
                if (
                    clientGeneration != generation ||
                    connectedGeneration != clientGeneration
                ) return
                collectConnectionEvents(events)
            }
        }

        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeGroups(message: OutboundGroupIterator) = Unit
        override fun writeLogs(messageList: LogIterator) = Unit
        override fun writeOutbounds(messageList: OutboundGroupItemIterator) = Unit
        override fun writeStatus(message: StatusMessage) = Unit
    }

    private fun collectConnectionEvents(events: ConnectionEvents) {
        val activeProfile = currentProfileId().coerceAtLeast(0L)
        if (events.reset) accumulator.reset(activeProfile)
        val iterator = events.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (event.type.toLong() == Libbox.ConnectionEventNew) {
                val connection = runCatching { event.connection }.getOrNull()
                if (connection == null || connection.closedAt > 0L) continue
                accumulator.recordNew(
                    connectionId = event.id,
                    profileId = connection.outbound.let {
                        profileIdForTrafficOutbound(
                            outbound = it,
                            sessionProxyTag = sessionProxyTag,
                            currentProfileId = activeProfile,
                        )
                    },
                    currentProfileId = activeProfile,
                    uplinkTotal = connection.uplinkTotal,
                    downlinkTotal = connection.downlinkTotal,
                    // The reset batch is a snapshot of traffic that pre-dates this subscription.
                    baselineOnly = events.reset,
                )
            } else {
                val closed = event.type.toLong() == Libbox.ConnectionEventClosed
                val finalConnection = if (closed) {
                    runCatching { event.connection }.getOrNull()
                } else {
                    null
                }
                accumulator.recordUpdate(
                    connectionId = event.id,
                    currentProfileId = activeProfile,
                    uplinkDelta = event.uplinkDelta,
                    downlinkDelta = event.downlinkDelta,
                    closed = closed,
                    finalUplinkTotal = finalConnection?.uplinkTotal,
                    finalDownlinkTotal = finalConnection?.downlinkTotal,
                )
            }
        }
    }

    private fun publishSample(clientGeneration: Long) {
        synchronized(stateLock) {
            if (
                clientGeneration != generation ||
                clientGeneration != connectedGeneration
            ) return
            val sampledAt = nowElapsedRealtime()
            val windowStartedAt = sampleWindowStartedAt.getAndSet(sampledAt)
            val elapsedMs = (sampledAt - windowStartedAt)
                .takeIf { windowStartedAt > 0L && it > 0L }
                ?: SAMPLE_INTERVAL_MS
            val activeProfile = currentProfileId().coerceAtLeast(0L)
            if (smoothedProfileId != activeProfile) {
                smoothedProfileId = activeProfile
                smoother.reset()
            }
            val raw = accumulator.sample(activeProfile)
            if (activeProfile <= 0L) {
                latest.set(RuntimeTrafficSnapshot.unavailable(sampledAt))
                return
            }
            val (uplink, downlink) = smoother.update(
                bytesPerSecond(raw.first, elapsedMs),
                bytesPerSecond(raw.second, elapsedMs),
            )
            latest.set(
                RuntimeTrafficSnapshot(
                    available = true,
                    profileId = activeProfile,
                    uplinkBytesPerSecond = uplink,
                    downlinkBytesPerSecond = downlink,
                    sampledAtElapsedRealtime = sampledAt,
                ),
            )
        }
    }

    /** Caller holds [stateLock], preventing an old callback from publishing after this reset. */
    private fun resetTrafficStateLocked() {
        smoothedProfileId = currentProfileId().coerceAtLeast(0L)
        accumulator.reset(smoothedProfileId)
        smoother.reset()
        val now = nowElapsedRealtime()
        sampleWindowStartedAt.set(now)
        latest.set(RuntimeTrafficSnapshot.unavailable(now))
    }

    private fun isRecentlyRequested(): Boolean {
        val requestedAt = lastRequestedAt.get()
        return requestedAt >= 0L && nowElapsedRealtime() - requestedAt <= IDLE_TIMEOUT_MS
    }

    private fun stopSubscription() {
        val state = synchronized(stateLock) {
            generation++
            connectedGeneration = -1L
            val job = subscriptionJob.also { subscriptionJob = null }
            val client = commandClient.also { commandClient = null }
            resetTrafficStateLocked()
            job to client
        }
        state.first?.cancel()
        runCatching { state.second?.disconnect() }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
        }
        stopSubscription()
        idleJob?.cancel()
        idleJob = null
        scope.cancel()
    }
}

/** Resolves both selector leaf tags and the single-node session outbound tag. */
internal fun profileIdForTrafficOutbound(
    outbound: String,
    sessionProxyTag: String,
    currentProfileId: Long,
): Long? {
    val normalizedCurrent = currentProfileId.coerceAtLeast(0L)
    if (outbound == sessionProxyTag) return normalizedCurrent.takeIf { it > 0L }
    return outbound
        .takeIf { it.startsWith(AutoNodeSelector.NODE_TAG_PREFIX) }
        ?.removePrefix(AutoNodeSelector.NODE_TAG_PREFIX)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
}
