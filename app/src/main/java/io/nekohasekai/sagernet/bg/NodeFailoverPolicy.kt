package io.nekohasekai.sagernet.bg

import java.util.ArrayDeque

/**
 * Pure state for failure-triggered node switching.
 *
 * Only real request-failure evidence may open a recovery attempt. Cooldowns, de-duplication and a
 * per-round exclusion set keep a broken group from bouncing endlessly between the same nodes.
 */
internal class NodeFailoverPolicy(
    private val failureThreshold: Int = 3,
    private val failureWindowMs: Long = 15_000L,
    private val failureDebounceMs: Long = 750L,
    private val switchCooldownMs: Long = 5_000L,
    private val exhaustedCooldownMs: Long = 5 * 60_000L,
) {
    private val failureTimes = ArrayDeque<Long>()
    private val failedProfiles = linkedSetOf<Long>()
    private var evidenceEpoch: NodeFailureEpoch? = null
    private var recoveringEpoch: NodeFailureEpoch? = null
    private var lastFailureAt = Long.MIN_VALUE
    private var blockedUntil = 0L
    private var resetRoundAt = 0L
    private var recovering = false

    init {
        require(failureThreshold >= 2)
        require(failureWindowMs > 0L && failureDebounceMs in 0 until failureWindowMs)
        require(switchCooldownMs > 0L && exhaustedCooldownMs >= switchCooldownMs)
    }

    @Synchronized
    fun recordRequestFailure(
        epoch: NodeFailureEpoch,
        now: Long,
        physicalNetworkAvailable: Boolean,
    ): Boolean {
        if (!physicalNetworkAvailable || recovering) return false
        resetExpiredRound(now)
        if (now < blockedUntil) return false
        if (evidenceEpoch != epoch) {
            clearFailureEvidence()
            evidenceEpoch = epoch
        }
        if (lastFailureAt != Long.MIN_VALUE && now - lastFailureAt < failureDebounceMs) return false
        lastFailureAt = now
        while (failureTimes.isNotEmpty() && now - failureTimes.first() > failureWindowMs) {
            failureTimes.removeFirst()
        }
        failureTimes.addLast(now)
        return failureTimes.size >= failureThreshold
    }

    @Synchronized
    fun beginRecovery(epoch: NodeFailureEpoch, now: Long): Boolean {
        resetExpiredRound(now)
        if (
            recovering || evidenceEpoch != epoch || now < blockedUntil ||
            failureTimes.size < failureThreshold
        ) return false
        recovering = true
        recoveringEpoch = epoch
        failureTimes.clear()
        return true
    }

    @Synchronized
    fun markSwitched(
        epoch: NodeFailureEpoch,
        failedProfileIds: Collection<Long>,
        now: Long,
    ): Boolean {
        if (!recovering || recoveringEpoch != epoch) return false
        require(failedProfileIds.isNotEmpty() && failedProfileIds.all { it > 0L })
        recovering = false
        recoveringEpoch = null
        evidenceEpoch = null
        failureTimes.clear()
        lastFailureAt = Long.MIN_VALUE
        failedProfiles += failedProfileIds
        blockedUntil = now + switchCooldownMs
        if (resetRoundAt == 0L) resetRoundAt = now + exhaustedCooldownMs
        return true
    }

    @Synchronized
    fun markNoCandidate(
        epoch: NodeFailureEpoch,
        failedProfileIds: Collection<Long>,
        now: Long,
    ): Boolean {
        if (!recovering || recoveringEpoch != epoch) return false
        failedProfiles += failedProfileIds.filter { it > 0L }
        recovering = false
        recoveringEpoch = null
        evidenceEpoch = null
        failureTimes.clear()
        lastFailureAt = Long.MIN_VALUE
        blockedUntil = now + exhaustedCooldownMs
        resetRoundAt = blockedUntil
        return true
    }

    @Synchronized
    fun cancelRecovery() {
        recovering = false
        recoveringEpoch = null
    }

    @Synchronized
    fun markPathHealthy(epoch: NodeFailureEpoch): Boolean {
        if (!recovering || recoveringEpoch != epoch) return false
        clearFailureEvidence()
        recovering = false
        recoveringEpoch = null
        return true
    }

    @Synchronized
    fun isRecovering(epoch: NodeFailureEpoch): Boolean = recovering && recoveringEpoch == epoch

    /** A command-channel reconnect must not make previously failed proxy nodes eligible again. */
    @Synchronized
    fun resetFailureEvidence() {
        clearFailureEvidence()
        recovering = false
        recoveringEpoch = null
    }

    @Synchronized
    fun resetForManualSelection() {
        clearFailureEvidence()
        failedProfiles.clear()
        blockedUntil = 0L
        resetRoundAt = 0L
        recovering = false
        recoveringEpoch = null
    }

    @Synchronized
    fun resetForNetworkChange() {
        resetForManualSelection()
    }

    @Synchronized
    fun excludedProfileIds(now: Long): Set<Long> {
        resetExpiredRound(now)
        return failedProfiles.toSet()
    }

    private fun resetExpiredRound(now: Long) {
        if (resetRoundAt > 0L && now >= resetRoundAt) {
            failedProfiles.clear()
            resetRoundAt = 0L
            blockedUntil = 0L
        }
    }

    private fun clearFailureEvidence() {
        failureTimes.clear()
        lastFailureAt = Long.MIN_VALUE
        evidenceEpoch = null
    }
}

/** Identity of the exact proxy path for which request failures were observed. */
internal data class NodeFailureEpoch(
    val currentTag: String,
    val stateRevision: Long,
    val networkGeneration: Long,
)

/** Only errors attributable to the currently selected outbound count as failure evidence. */
internal fun isDefiniteCurrentNodeFailure(message: String, currentTag: String): Boolean {
    if (message.isBlank() || currentTag.isBlank()) return false
    val normalized = message.lowercase()
    val tag = currentTag.lowercase()
    val belongsToCurrentNode = normalized.contains("[$tag]") ||
        normalized.contains("($tag)") ||
        normalized.contains("/$tag:") ||
        normalized.contains(" $tag: ")
    if (!belongsToCurrentNode) return false
    return DEFINITE_NODE_FAILURE_MARKERS.any(normalized::contains)
}

private val DEFINITE_NODE_FAILURE_MARKERS = listOf(
    "connection refused",
    "connection reset",
    "context deadline exceeded",
    "i/o timeout",
    "network is unreachable",
    "no route to host",
    "tls handshake",
    "handshake failed",
    "authentication failed",
    "bad certificate",
    "server rejected",
    "transport is closing",
    "unexpected eof",
)
