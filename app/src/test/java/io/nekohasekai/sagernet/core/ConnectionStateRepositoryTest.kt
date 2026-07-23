package io.nekohasekai.sagernet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateRepositoryTest {
    @Test
    fun bindingAndDeadSourcesCannotStartFromAnUnknownSnapshot() {
        val repository = ProcessConnectionStateRepository()
        val source = Any()

        repository.beginBinding(source)
        assertEquals(ConnectionProjection.Binding, repository.projection)
        assertFalse(repository.canStart)
        assertFalse(repository.canStop)

        repository.markDead(source)
        assertEquals(ConnectionProjection.Dead, repository.projection)
        assertEquals(ConnectionState.Idle, repository.stateOrIdle)
        assertFalse(repository.canStart)
    }

    @Test
    fun boundStatePreservesExistingStartAndStopSemantics() {
        val repository = ProcessConnectionStateRepository()
        val source = Any()

        repository.publish(source, ConnectionState.Idle)
        assertTrue(repository.canStart)
        assertFalse(repository.canStop)

        repository.publish(source, ConnectionState.Connected)
        assertFalse(repository.canStart)
        assertTrue(repository.canStop)
        assertEquals(ConnectionState.Connected, repository.stateOrIdle)
    }

    @Test
    fun oneDisconnectedClientCannotEraseAnotherLiveBinderSnapshot() {
        val repository = ProcessConnectionStateRepository()
        val activity = Any()
        val tile = Any()

        repository.publish(activity, ConnectionState.Connected)
        repository.beginBinding(tile)
        repository.remove(tile)

        assertEquals(
            ConnectionProjection.Bound(ConnectionState.Connected),
            repository.projection,
        )
    }

    @Test
    fun deadClientCannotOverrideAnotherBoundClient() {
        val repository = ProcessConnectionStateRepository()
        val activity = Any()
        val tile = Any()

        repository.publish(activity, ConnectionState.Connected)
        repository.publish(tile, ConnectionState.Connected)
        repository.markDead(tile)

        assertEquals(ConnectionState.Connected, repository.stateOrIdle)
        assertTrue(repository.canStop)
    }
}
