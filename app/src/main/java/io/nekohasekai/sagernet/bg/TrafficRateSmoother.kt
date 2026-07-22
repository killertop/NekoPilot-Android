package io.nekohasekai.sagernet.bg

import kotlin.math.roundToLong

/** Small EWMA that removes one-second traffic spikes without making Home feel delayed. */
internal class TrafficRateSmoother(
    private val newSampleWeight: Double = 0.4,
) {
    init {
        require(newSampleWeight in 0.0..1.0)
    }

    private var uplink: Double? = null
    private var downlink: Double? = null
    private var uplinkZeroSamples = 0
    private var downlinkZeroSamples = 0

    @Synchronized
    fun update(rawUplink: Long, rawDownlink: Long): Pair<Long, Long> {
        val nextUplink = smooth(uplink, rawUplink.coerceAtLeast(0L), uplinkZeroSamples)
        uplink = nextUplink.first
        uplinkZeroSamples = nextUplink.second
        val nextDownlink = smooth(downlink, rawDownlink.coerceAtLeast(0L), downlinkZeroSamples)
        downlink = nextDownlink.first
        downlinkZeroSamples = nextDownlink.second
        return uplink!!.roundToLong() to downlink!!.roundToLong()
    }

    @Synchronized
    fun reset() {
        uplink = null
        downlink = null
        uplinkZeroSamples = 0
        downlinkZeroSamples = 0
    }

    private fun smooth(previous: Double?, current: Long, zeroSamples: Int): Pair<Double, Int> {
        val nextZeroSamples = if (current == 0L) zeroSamples + 1 else 0
        if (nextZeroSamples >= 2) return 0.0 to nextZeroSamples
        val value = previous?.let {
            it * (1.0 - newSampleWeight) + current * newSampleWeight
        } ?: current.toDouble()
        return value to nextZeroSamples
    }
}

/** Aggregates connection deltas for one active node and ignores direct/LAN/old-node traffic. */
internal class NodeTrafficAccumulator {
    private data class AccountedConnection(
        val profileId: Long,
        var uplinkTotal: Long,
        var downlinkTotal: Long,
    )

    private val connections = HashMap<String, AccountedConnection>()
    private var activeProfileId = 0L
    private var pendingUplink = 0L
    private var pendingDownlink = 0L

    @Synchronized
    fun reset(activeProfileId: Long, clearConnections: Boolean = true) {
        if (clearConnections) connections.clear()
        this.activeProfileId = activeProfileId.coerceAtLeast(0L)
        pendingUplink = 0L
        pendingDownlink = 0L
    }

    @Synchronized
    fun recordNew(
        connectionId: String,
        profileId: Long?,
        currentProfileId: Long,
        uplinkTotal: Long,
        downlinkTotal: Long,
        baselineOnly: Boolean,
    ) {
        selectProfile(currentProfileId)
        if (profileId == null) {
            connections.remove(connectionId)
            return
        }
        val safeUplink = uplinkTotal.coerceAtLeast(0L)
        val safeDownlink = downlinkTotal.coerceAtLeast(0L)
        connections[connectionId] = AccountedConnection(
            profileId = profileId,
            uplinkTotal = safeUplink,
            downlinkTotal = safeDownlink,
        )
        if (!baselineOnly) addIfCurrent(profileId, safeUplink, safeDownlink)
    }

    @Synchronized
    fun recordUpdate(
        connectionId: String,
        currentProfileId: Long,
        uplinkDelta: Long,
        downlinkDelta: Long,
        closed: Boolean,
        finalUplinkTotal: Long? = null,
        finalDownlinkTotal: Long? = null,
    ) {
        selectProfile(currentProfileId)
        val connection = connections[connectionId] ?: return
        val safeUplinkDelta = uplinkDelta.coerceAtLeast(0L)
        val safeDownlinkDelta = downlinkDelta.coerceAtLeast(0L)
        addIfCurrent(connection.profileId, safeUplinkDelta, safeDownlinkDelta)
        connection.uplinkTotal = saturatingAdd(connection.uplinkTotal, safeUplinkDelta)
        connection.downlinkTotal = saturatingAdd(connection.downlinkTotal, safeDownlinkDelta)
        if (!closed) return

        // A connection may open and close between two libbox traffic ticks. CLOSED carries its
        // final totals but no delta, so account only the remainder that UPDATE has not reported.
        val uplinkRemainder = finalUplinkTotal
            ?.coerceAtLeast(0L)
            ?.minus(connection.uplinkTotal)
            ?.coerceAtLeast(0L)
            ?: 0L
        val downlinkRemainder = finalDownlinkTotal
            ?.coerceAtLeast(0L)
            ?.minus(connection.downlinkTotal)
            ?.coerceAtLeast(0L)
            ?: 0L
        addIfCurrent(connection.profileId, uplinkRemainder, downlinkRemainder)
        connections.remove(connectionId)
    }

    @Synchronized
    fun sample(currentProfileId: Long): Pair<Long, Long> {
        selectProfile(currentProfileId)
        return (pendingUplink to pendingDownlink).also {
            pendingUplink = 0L
            pendingDownlink = 0L
        }
    }

    private fun selectProfile(profileId: Long) {
        val normalized = profileId.coerceAtLeast(0L)
        if (activeProfileId == normalized) return
        activeProfileId = normalized
        pendingUplink = 0L
        pendingDownlink = 0L
    }

    private fun addIfCurrent(profileId: Long?, uplinkDelta: Long, downlinkDelta: Long) {
        if (profileId != activeProfileId || activeProfileId <= 0L) return
        pendingUplink = saturatingAdd(pendingUplink, uplinkDelta)
        pendingDownlink = saturatingAdd(pendingDownlink, downlinkDelta)
    }

    private fun saturatingAdd(current: Long, delta: Long): Long {
        val safeDelta = delta.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - current < safeDelta) Long.MAX_VALUE else current + safeDelta
    }
}

/** Converts bytes observed during an arbitrary monotonic interval into bytes per second. */
internal fun bytesPerSecond(bytes: Long, elapsedMs: Long): Long {
    if (bytes <= 0L || elapsedMs <= 0L) return 0L
    return (bytes.toDouble() * 1_000.0 / elapsedMs.toDouble())
        .roundToLong()
        .coerceAtLeast(0L)
}
