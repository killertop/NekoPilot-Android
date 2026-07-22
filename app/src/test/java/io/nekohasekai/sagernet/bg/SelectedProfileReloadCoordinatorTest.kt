package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.core.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedProfileReloadCoordinatorTest {
    @Test
    fun selectionDuringConnectRestartsOnlyAfterCleanIdleTransition() {
        assertTrue(
            shouldStartAfterSelection(
                requestedWhileConnecting = true,
                currentState = ConnectionState.Idle,
            ),
        )
        assertFalse(
            shouldStartAfterSelection(
                requestedWhileConnecting = true,
                currentState = ConnectionState.Error,
            ),
        )
    }

    @Test
    fun idleSelectionDoesNotStartAStoppedService() {
        assertFalse(
            shouldStartAfterSelection(
                requestedWhileConnecting = false,
                currentState = ConnectionState.Idle,
            ),
        )
    }
}
