package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.core.AutoNodeSelectionPhase
import io.nekohasekai.sagernet.core.AutoNodeSelectionStatus
import io.nekohasekai.sagernet.core.ConnectionState
import io.nekohasekai.sagernet.core.RuntimeTrafficSnapshot

internal sealed interface HomeConnectionPresentation {
    data object Connecting : HomeConnectionPresentation
    data class Connected(val traffic: RuntimeTrafficSnapshot?) : HomeConnectionPresentation
    data object Switching : HomeConnectionPresentation
    data object Stopping : HomeConnectionPresentation
    data class Failed(val technicalMessage: String) : HomeConnectionPresentation
    data object Disconnected : HomeConnectionPresentation
    data object AutoRecovering : HomeConnectionPresentation
    data class AutoSwitched(val latencyMs: Int) : HomeConnectionPresentation
    data object AutoFailed : HomeConnectionPresentation
}

internal data class HomeConnectionInput(
    val profileId: Long,
    val selectedProfileId: Long,
    val currentProfileId: Long,
    val state: ConnectionState,
    val nodeTestStatus: Int,
    val lastError: String,
    val lastErrorProfileId: Long,
    val lastErrorAtMillis: Long,
    val automaticSelection: AutoNodeSelectionStatus?,
    val traffic: RuntimeTrafficSnapshot?,
)

/**
 * Returns only the rows whose rendered traffic text can have changed between two snapshots.
 *
 * Home polls the Binder once per second while visible. Restricting the resulting redraw to these
 * profile ids avoids recomputing every visible row's selection, test and action state just to
 * update the active node's rate label.
 */
internal fun trafficRefreshProfileIds(
    previous: RuntimeTrafficSnapshot?,
    next: RuntimeTrafficSnapshot?,
): Set<Long> = buildSet {
    previous?.profileId?.takeIf { it > 0L }?.let(::add)
    next?.profileId?.takeIf { it > 0L }?.let(::add)
}

internal object HomeConnectionPresentationResolver {
    const val ERROR_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    fun resolve(
        input: HomeConnectionInput,
        nowMillis: Long,
        nowElapsedRealtime: Long,
    ): HomeConnectionPresentation? {
        if (input.state == ConnectionState.Connected) {
            automaticSelection(input.profileId, input.automaticSelection, nowMillis)?.let {
                return it
            }
        }
        val selected = input.profileId == input.selectedProfileId
        val current = input.profileId == input.currentProfileId
        if (!selected && !(current && input.state in currentProfileVisibleStates)) return null

        return when (input.state) {
            ConnectionState.Preparing,
            ConnectionState.Connecting -> HomeConnectionPresentation.Connecting

            ConnectionState.Connected -> if (current) {
                HomeConnectionPresentation.Connected(
                    input.traffic?.takeIf {
                        it.profileId == input.profileId && it.isFresh(nowElapsedRealtime)
                    },
                )
            } else {
                HomeConnectionPresentation.Switching
            }

            ConnectionState.Stopping -> if (current) {
                HomeConnectionPresentation.Stopping
            } else {
                HomeConnectionPresentation.Switching
            }

            ConnectionState.Error,
            ConnectionState.Idle -> recentError(input, nowMillis)
                ?: HomeConnectionPresentation.Disconnected.takeIf { input.nodeTestStatus == 0 }
        }
    }

    private fun automaticSelection(
        profileId: Long,
        status: AutoNodeSelectionStatus?,
        nowMillis: Long,
    ): HomeConnectionPresentation? {
        if (status == null || status.profileId != profileId) return null
        if (status.until > 0L && status.until <= nowMillis) return null
        return when (status.phase) {
            AutoNodeSelectionPhase.RECOVERING -> HomeConnectionPresentation.AutoRecovering
            AutoNodeSelectionPhase.SWITCHED ->
                HomeConnectionPresentation.AutoSwitched(status.latencyMs)
            AutoNodeSelectionPhase.FAILED -> HomeConnectionPresentation.AutoFailed
        }
    }

    private fun recentError(
        input: HomeConnectionInput,
        nowMillis: Long,
    ): HomeConnectionPresentation.Failed? = input.lastError
        .takeIf {
            it.isNotBlank() && input.lastErrorProfileId == input.profileId &&
                nowMillis - input.lastErrorAtMillis in 0 until ERROR_MAX_AGE_MS
        }
        ?.let(HomeConnectionPresentation::Failed)

    private val currentProfileVisibleStates = setOf(
        ConnectionState.Connected,
        ConnectionState.Stopping,
    )
}
