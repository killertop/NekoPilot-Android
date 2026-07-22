package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.core.ConnectionState
import io.nekohasekai.sagernet.core.ConnectionStopResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMachineTest {
    @Test
    fun wireValuesAreDecodedWithoutIndexingPastEnumBounds() {
        ConnectionState.entries.forEach { state ->
            assertEquals(state, ConnectionState.fromWireValue(state.ordinal))
        }
        assertNull(ConnectionState.fromWireValue(-1))
        assertNull(ConnectionState.fromWireValue(ConnectionState.entries.size))
        assertNull(ConnectionState.fromWireValue(Int.MAX_VALUE))
    }

    @Test
    fun normalConnectionUsesEveryLifecycleBoundary() {
        val machine = ConnectionStateMachine()

        assertTrue(machine.beginStart())
        assertEquals(ConnectionState.Preparing, machine.state)
        assertTrue(machine.markConnecting())
        assertTrue(machine.markConnected())
        assertEquals(ConnectionState.Connected, machine.state)

        assertTrue(machine.requestStop(restart = false).shouldStop)
        assertEquals(ConnectionState.Stopping, machine.state)
        val completion = machine.finishStop(ConnectionStopResult.Completed)
        assertEquals(ConnectionState.Idle, completion.state)
        assertFalse(completion.shouldRestart)
    }

    @Test
    fun duplicateStartAndStopAreIdempotent() {
        val machine = ConnectionStateMachine()

        assertTrue(machine.beginStart())
        assertFalse(machine.beginStart())
        assertTrue(machine.requestStop(restart = false).shouldStop)
        assertFalse(machine.requestStop(restart = false).shouldStop)
        machine.finishStop(ConnectionStopResult.Completed)
        assertFalse(machine.requestStop(restart = false).shouldStop)
    }

    @Test
    fun restartRequestsDuringStopAreCoalesced() {
        val machine = ConnectionStateMachine()
        machine.beginStart()
        machine.markConnecting()
        machine.markConnected()

        assertTrue(machine.requestStop(restart = true).shouldStop)
        assertFalse(machine.requestStop(restart = true).shouldStop)
        assertTrue(machine.finishStop(ConnectionStopResult.Completed).shouldRestart)
        assertTrue(machine.beginStart())
    }

    @Test
    fun failedTeardownEndsInErrorWithoutRestartLoop() {
        val machine = ConnectionStateMachine()
        machine.beginStart()
        machine.markConnecting()

        machine.requestStop(restart = true)
        val completion = machine.finishStop(ConnectionStopResult.Failed)
        assertEquals(ConnectionState.Error, completion.state)
        assertFalse(completion.shouldRestart)
        assertTrue(machine.beginStart())
    }

    @Test
    fun processRestartStartsFromIdleAndCannotSkipPreparation() {
        val machine = ConnectionStateMachine()

        assertEquals(ConnectionState.Idle, machine.state)
        assertFalse(machine.markConnecting())
        assertFalse(machine.markConnected())
        assertTrue(machine.beginStart())
    }
}
