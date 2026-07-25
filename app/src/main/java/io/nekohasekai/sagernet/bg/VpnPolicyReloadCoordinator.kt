package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.applicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns app-scoped VPN policy reconnect requests from short-lived UI screens.
 *
 * The final request intentionally survives Activity destruction: the selected package policy is
 * already persisted and must still reach the running VPN process through its controlled
 * stop/start state machine.
 */
internal object VpnPolicyReloadCoordinator {
    private val debouncer = DebouncedApplicationAction(
        scope = applicationScope,
        delayMillis = 350L,
    ) {
        SagerNet.requestVpnPolicyReconnect()
    }

    fun request() = debouncer.request()
}

internal class DebouncedApplicationAction(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val action: suspend () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var pending: Job? = null

    fun request() {
        synchronized(lock) {
            pending?.cancel()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMillis)
                action()
            }
            pending = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (pending === job) pending = null
                }
            }
            job.start()
        }
    }

    override fun close() {
        synchronized(lock) {
            pending?.cancel()
            pending = null
        }
    }
}
