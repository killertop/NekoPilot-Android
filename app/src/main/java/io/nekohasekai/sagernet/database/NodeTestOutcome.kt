package io.nekohasekai.sagernet.database

/**
 * Atomic node-test result used at the data boundary.
 *
 * ProxyEntity keeps its existing Room columns for compatibility, while callers can no longer
 * update status, latency, speed and error independently and accidentally retain stale values.
 */
sealed interface NodeTestOutcome {
    data class Available(
        val latencyMs: Int,
        val downloadMbps: Double?,
    ) : NodeTestOutcome

    data class Unavailable(
        val reason: FailureReason,
        val message: String,
    ) : NodeTestOutcome

    enum class FailureReason {
        MISSING_PLUGIN,
        TEST_FAILED,
    }
}

internal fun ProxyEntity.clearNodeTestOutcome() {
    status = 0
    ping = 0
    downloadMbps = null
    error = null
}

internal fun ProxyEntity.applyNodeTestOutcome(outcome: NodeTestOutcome) {
    when (outcome) {
        is NodeTestOutcome.Available -> {
            require(outcome.latencyMs >= 0) { "Node-test latency cannot be negative" }
            status = 1
            ping = outcome.latencyMs
            downloadMbps = outcome.downloadMbps
            error = null
        }

        is NodeTestOutcome.Unavailable -> {
            status = when (outcome.reason) {
                NodeTestOutcome.FailureReason.MISSING_PLUGIN -> 2
                NodeTestOutcome.FailureReason.TEST_FAILED -> 3
            }
            ping = 0
            downloadMbps = null
            error = outcome.message
        }
    }
}
