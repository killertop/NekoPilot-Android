package io.nekohasekai.sagernet.ui

internal data class NodeTestSnapshot(
    val status: Int,
    val latencyMs: Int,
    val downloadMbps: Double?,
    val error: String?,
)

internal sealed interface NodeTestDisplayState {
    data object Testing : NodeTestDisplayState
    data class Available(val latencyMs: Int, val downloadMbps: Double?) : NodeTestDisplayState
    data class Unavailable(
        val error: String?,
        val translateFriendlyMessage: Boolean,
    ) : NodeTestDisplayState
    data class RuntimeUnavailable(val error: String?) : NodeTestDisplayState
}

internal object NodeTestStatusResolver {
    fun resolve(running: Boolean, snapshot: NodeTestSnapshot): NodeTestDisplayState? = when {
        running && snapshot.status == 0 -> NodeTestDisplayState.Testing
        snapshot.status == 1 -> NodeTestDisplayState.Available(
            snapshot.latencyMs,
            snapshot.downloadMbps,
        )
        snapshot.status == 2 -> NodeTestDisplayState.Unavailable(
            snapshot.error?.takeIf(String::isNotBlank),
            translateFriendlyMessage = false,
        )
        snapshot.status == 3 -> NodeTestDisplayState.Unavailable(
            snapshot.error ?: "<?>",
            translateFriendlyMessage = true,
        )
        snapshot.status == 4 -> NodeTestDisplayState.RuntimeUnavailable(
            snapshot.error?.takeIf(String::isNotBlank),
        )
        else -> null
    }
}
