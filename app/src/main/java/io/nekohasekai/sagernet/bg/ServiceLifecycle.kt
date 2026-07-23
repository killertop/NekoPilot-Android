package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Owns asynchronous work and late resource publication for one Service instance. */
internal class ServiceLifecycle(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val lock = Any()
    private val rootJob = SupervisorJob()

    val scope = CoroutineScope(rootJob + dispatcher)

    @Volatile
    var destroyed = false
        private set

    fun commitIfAlive(block: () -> Unit): Boolean = synchronized(lock) {
        if (destroyed || !rootJob.isActive) return@synchronized false
        block()
        true
    }

    override fun close() {
        synchronized(lock) {
            destroyed = true
        }
        scope.cancel()
    }
}
