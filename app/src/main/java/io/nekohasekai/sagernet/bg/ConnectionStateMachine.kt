package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.core.ConnectionState
import io.nekohasekai.sagernet.core.ConnectionStopResult

/**
 * Authoritative lifecycle for one Android VPN service instance.
 *
 * UI state is only a projection delivered over Binder. Core, TUN and notification ownership all
 * follow this machine so repeated commands cannot create a second connection lifecycle.
 */
internal class ConnectionStateMachine {
    data class StopDecision(
        val shouldStop: Boolean,
        val stateChanged: Boolean,
    )

    data class StopCompletion(
        val state: ConnectionState,
        val shouldRestart: Boolean,
    )

    @Volatile
    var state: ConnectionState = ConnectionState.Idle
        private set

    private var restartRequested = false

    @Synchronized
    fun beginStart(): Boolean {
        if (!state.canStart) return false
        restartRequested = false
        state = ConnectionState.Preparing
        return true
    }

    @Synchronized
    fun markConnecting(): Boolean = transition(
        expected = ConnectionState.Preparing,
        target = ConnectionState.Connecting,
    )

    @Synchronized
    fun markConnected(): Boolean = transition(
        expected = ConnectionState.Connecting,
        target = ConnectionState.Connected,
    )

    @Synchronized
    fun requestStop(restart: Boolean): StopDecision {
        if (restart) restartRequested = true
        return when (state) {
            ConnectionState.Preparing,
            ConnectionState.Connecting,
            ConnectionState.Connected -> {
                state = ConnectionState.Stopping
                StopDecision(shouldStop = true, stateChanged = true)
            }

            ConnectionState.Stopping -> StopDecision(shouldStop = false, stateChanged = false)
            ConnectionState.Idle,
            ConnectionState.Error -> {
                // There is nothing to tear down. A caller wanting a fresh start can invoke
                // beginStart() after observing this terminal state.
                restartRequested = false
                StopDecision(shouldStop = false, stateChanged = false)
            }
        }
    }

    @Synchronized
    fun finishStop(result: ConnectionStopResult): StopCompletion {
        check(state == ConnectionState.Stopping) { "Cannot finish stop from $state" }
        val restart = restartRequested && result is ConnectionStopResult.Completed
        restartRequested = false
        state = when (result) {
            ConnectionStopResult.Completed -> ConnectionState.Idle
            is ConnectionStopResult.Failed -> ConnectionState.Error
        }
        return StopCompletion(state, restart)
    }

    @Synchronized
    fun failPreparing(): Boolean {
        if (state != ConnectionState.Preparing) return false
        restartRequested = false
        state = ConnectionState.Error
        return true
    }

    @Synchronized
    private fun transition(expected: ConnectionState, target: ConnectionState): Boolean {
        if (state == target) return false
        if (state != expected) return false
        state = target
        return true
    }
}
