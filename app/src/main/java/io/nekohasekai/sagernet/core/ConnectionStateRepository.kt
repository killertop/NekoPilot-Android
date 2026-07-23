package io.nekohasekai.sagernet.core

import java.util.IdentityHashMap

/**
 * Process-local projection of the connection state owned by one or more Service/Binder sources.
 *
 * Android runs the VPN in a separate process, so this deliberately does not pretend that a
 * volatile field is cross-process state. Each process learns state from its local Service or an
 * active Binder connection, and unknown lifecycle phases cannot issue start/stop commands.
 */
sealed interface ConnectionProjection {
    data object Unbound : ConnectionProjection
    data object Binding : ConnectionProjection
    data class Bound(val state: ConnectionState) : ConnectionProjection
    data object Dead : ConnectionProjection
}

internal class ProcessConnectionStateRepository {
    private data class SourceState(
        val projection: ConnectionProjection,
        val revision: Long,
    )

    private val lock = Any()
    private val sources = IdentityHashMap<Any, SourceState>()
    private var revision = 0L

    @Volatile
    var projection: ConnectionProjection = ConnectionProjection.Unbound
        private set

    val stateOrIdle: ConnectionState
        get() = (projection as? ConnectionProjection.Bound)?.state ?: ConnectionState.Idle

    val canStart: Boolean
        get() = (projection as? ConnectionProjection.Bound)?.state?.canStart == true

    val canStop: Boolean
        get() = (projection as? ConnectionProjection.Bound)?.state?.canStop == true

    fun beginBinding(source: Any) = update(source, ConnectionProjection.Binding)

    fun publish(source: Any, state: ConnectionState) =
        update(source, ConnectionProjection.Bound(state))

    fun markDead(source: Any) = update(source, ConnectionProjection.Dead)

    fun remove(source: Any) {
        synchronized(lock) {
            sources.remove(source)
            projection = aggregateLocked()
        }
    }

    private fun update(source: Any, next: ConnectionProjection) {
        synchronized(lock) {
            sources[source] = SourceState(next, ++revision)
            projection = aggregateLocked()
        }
    }

    private fun aggregateLocked(): ConnectionProjection {
        if (sources.isEmpty()) return ConnectionProjection.Unbound
        return sources.values
            .filter { it.projection is ConnectionProjection.Bound }
            .maxByOrNull(SourceState::revision)
            ?.projection
            ?: sources.values
                .filter { it.projection is ConnectionProjection.Binding }
                .maxByOrNull(SourceState::revision)
                ?.projection
            ?: ConnectionProjection.Dead
    }
}

object ConnectionStateRepository {
    private val delegate = ProcessConnectionStateRepository()

    val projection: ConnectionProjection get() = delegate.projection
    val stateOrIdle: ConnectionState get() = delegate.stateOrIdle
    val canStart: Boolean get() = delegate.canStart
    val canStop: Boolean get() = delegate.canStop

    fun beginBinding(source: Any) = delegate.beginBinding(source)
    fun publish(source: Any, state: ConnectionState) = delegate.publish(source, state)
    fun markDead(source: Any) = delegate.markDead(source)
    fun remove(source: Any) = delegate.remove(source)
}
