package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.applicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns app-scoped VPN policy reloads requested by short-lived UI screens.
 *
 * The final reload intentionally survives Activity destruction: the selected package policy is
 * already persisted and must still reach the running VPN process.
 */
internal object VpnPolicyReloadCoordinator {
    private val debouncer = DebouncedApplicationAction(
        scope = applicationScope,
        delayMillis = 350L,
    ) {
        withContext(Dispatchers.IO) { SagerNet.reloadService() }
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
