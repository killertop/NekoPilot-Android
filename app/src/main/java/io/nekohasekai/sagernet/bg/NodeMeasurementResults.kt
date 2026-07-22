package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.ProxyEntity

/**
 * Completes one measurement batch against its immutable configuration snapshots.
 *
 * Missing results are failures, not permission to retain an earlier successful latency. Keeping
 * the snapshot's configRevision lets Room reject this whole row if the node was edited or updated
 * while the test was running.
 */
internal fun completeNodeMeasurements(
    candidates: Collection<ProxyEntity>,
    successfulLatencies: Map<Long, Int>,
    failureMessage: String,
): List<ProxyEntity> = candidates
    .distinctBy(ProxyEntity::id)
    .map { snapshot ->
        snapshot.copy().apply {
            val latency = successfulLatencies[id]?.takeIf { it > 0 }
            if (latency != null) {
                status = 1
                ping = latency
                error = null
            } else {
                status = 3
                ping = 0
                error = failureMessage
            }
            downloadMbps = null
        }
    }
