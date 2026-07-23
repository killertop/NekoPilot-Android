package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.core.ConnectionState
import io.nekohasekai.sagernet.core.ConnectionProjection
import io.nekohasekai.sagernet.core.ConnectionStateRepository
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coalesces node selections without tying the actual VPN reload to a Fragment lifecycle.
 *
 * A user can tap several rows while the service is Connecting/Stopping, or leave Home before
 * the debounce expires. The final desired node must still become the active core configuration.
 */
object SelectedProfileReloadCoordinator {
    private const val DEBOUNCE_MS = 150L
    private const val MAX_STATE_WAIT_MS = 60_000L // includes slow device startup

    private var pendingJob: Job? = null

    @Synchronized
    fun request(profileId: Long, force: Boolean = false) {
        pendingJob?.cancel()
        val requestedWhileConnecting =
            (ConnectionStateRepository.projection as? ConnectionProjection.Bound)?.state?.let {
                it == ConnectionState.Preparing || it == ConnectionState.Connecting
            } == true
        pendingJob = applicationScope.launch(Dispatchers.Default) {
            delay(DEBOUNCE_MS)
            var reloadRequested = false
            var pollAttempt = 0
            var remainingWaitMs = MAX_STATE_WAIT_MS
            while (remainingWaitMs > 0L) {
                if (DataStore.selectedProxy != profileId) return@launch
                val projection = ConnectionStateRepository.projection
                if (projection !is ConnectionProjection.Bound) {
                    val delayMs = selectedProfileReloadPollDelayMs(pollAttempt++)
                        .coerceAtMost(remainingWaitMs)
                    delay(delayMs)
                    remainingWaitMs -= delayMs
                    continue
                }
                when (val currentState = projection.state) {
                    ConnectionState.Connected -> {
                        // currentProfile is written by the service process; force a refresh before
                        // deciding that the desired profile is already active.
                        withContext(Dispatchers.IO) {
                            DataStore.configurationStore.refresh()
                        }
                        if (force || DataStore.currentProfile != profileId) SagerNet.reloadService()
                        return@launch
                    }

                    ConnectionState.Preparing,
                    ConnectionState.Connecting -> {
                        // The old attempt may otherwise fail and leave the newly selected node
                        // idle. Ask the service to restart once its receiver is ready, and keep
                        // polling in case this first broadcast raced receiver registration.
                        if (requestedWhileConnecting && !reloadRequested) {
                            SagerNet.reloadService()
                            reloadRequested = true
                        }
                        val delayMs = selectedProfileReloadPollDelayMs(pollAttempt++)
                            .coerceAtMost(remainingWaitMs)
                        delay(delayMs)
                        remainingWaitMs -= delayMs
                    }

                    ConnectionState.Stopping -> {
                        val delayMs = selectedProfileReloadPollDelayMs(pollAttempt++)
                            .coerceAtMost(remainingWaitMs)
                        delay(delayMs)
                        remainingWaitMs -= delayMs
                    }
                    ConnectionState.Idle, ConnectionState.Error -> {
                        if (
                            shouldStartAfterSelection(requestedWhileConnecting, currentState) &&
                            DataStore.selectedProxy == profileId
                        ) {
                            SagerNet.startService()
                        }
                        return@launch
                    }
                }
            }
            Logs.w("Timed out waiting to reload selected profile $profileId")
        }
    }

    @Synchronized
    fun cancel() {
        pendingJob?.cancel()
        pendingJob = null
    }
}

/**
 * Keep node switches responsive during the first seconds of a service transition, then back off
 * while waiting for a Binder state that may never arrive. This replaces a minute of 10 Hz polling
 * from an application-wide coroutine when Home has already gone away.
 */
internal fun selectedProfileReloadPollDelayMs(attempt: Int): Long = when {
    attempt < 10 -> 100L
    attempt < 20 -> 250L
    attempt < 40 -> 500L
    else -> 1_000L
}

internal fun shouldStartAfterSelection(
    requestedWhileConnecting: Boolean,
    currentState: ConnectionState,
): Boolean = requestedWhileConnecting && currentState == ConnectionState.Idle
