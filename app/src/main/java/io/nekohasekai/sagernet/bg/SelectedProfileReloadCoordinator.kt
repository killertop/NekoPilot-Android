package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.core.ConnectionState
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
    private const val STATE_POLL_MS = 100L
    private const val MAX_STATE_POLLS = 600 // one minute, including slow device startup

    private var pendingJob: Job? = null

    @Synchronized
    fun request(profileId: Long, force: Boolean = false) {
        pendingJob?.cancel()
        val requestedWhileConnecting = DataStore.serviceState.let {
            it == ConnectionState.Preparing || it == ConnectionState.Connecting
        }
        pendingJob = applicationScope.launch(Dispatchers.Default) {
            delay(DEBOUNCE_MS)
            var reloadRequested = false
            repeat(MAX_STATE_POLLS) {
                if (DataStore.selectedProxy != profileId) return@launch
                when (DataStore.serviceState) {
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
                        delay(STATE_POLL_MS)
                    }

                    ConnectionState.Stopping -> delay(STATE_POLL_MS)
                    ConnectionState.Idle, ConnectionState.Error -> {
                        if (requestedWhileConnecting && DataStore.selectedProxy == profileId) {
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
