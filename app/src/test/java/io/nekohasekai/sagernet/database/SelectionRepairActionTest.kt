package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionRepairActionTest {
    @Test
    fun activeRemovalReloadsPersistedSelectionWhileRunning() {
        assertEquals(
            SelectionRepairAction.ReloadSelectedProfile(42L),
            selectionRepairAction(
                connectionStarted = true,
                activeRemoved = true,
                selectionRemoved = false,
                selectedProfileId = 42L,
            ),
        )
    }

    @Test
    fun selectedRemovalStopsInsteadOfAutoConnectingFallback() {
        assertEquals(
            SelectionRepairAction.StopService,
            selectionRepairAction(
                connectionStarted = true,
                activeRemoved = true,
                selectionRemoved = true,
                selectedProfileId = 42L,
            ),
        )
    }

    @Test
    fun unrelatedRemovalDoesNothing() {
        assertEquals(
            SelectionRepairAction.None,
            selectionRepairAction(
                connectionStarted = true,
                activeRemoved = false,
                selectionRemoved = false,
                selectedProfileId = 42L,
            ),
        )
    }
}
