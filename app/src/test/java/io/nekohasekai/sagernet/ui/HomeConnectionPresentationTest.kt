package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.core.AutoNodeSelectionPhase
import io.nekohasekai.sagernet.core.AutoNodeSelectionStatus
import io.nekohasekai.sagernet.core.ConnectionState
import io.nekohasekai.sagernet.core.RuntimeTrafficSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeConnectionPresentationTest {
    @Test
    fun staleErrorDoesNotHideDisconnectedState() {
        val input = input(
            state = ConnectionState.Error,
            lastError = "old failure",
            lastErrorProfileId = 7L,
            lastErrorAtMillis = 1L,
        )

        assertEquals(
            HomeConnectionPresentation.Disconnected,
            HomeConnectionPresentationResolver.resolve(
                input,
                nowMillis = HomeConnectionPresentationResolver.ERROR_MAX_AGE_MS + 1L,
                nowElapsedRealtime = 0L,
            ),
        )
    }

    @Test
    fun lateTrafficFromPreviousProfileIsNotPresented() {
        val input = input(
            state = ConnectionState.Connected,
            traffic = RuntimeTrafficSnapshot(true, 9L, 10L, 20L, 1_000L),
        )

        assertEquals(
            HomeConnectionPresentation.Connected(null),
            HomeConnectionPresentationResolver.resolve(input, 1_000L, 1_100L),
        )
    }

    @Test
    fun trafficRefreshTargetsOnlyProfilesWhoseRateCanChange() {
        val previous = RuntimeTrafficSnapshot(true, 7L, 10L, 20L, 1_000L)
        val next = RuntimeTrafficSnapshot(true, 9L, 30L, 40L, 2_000L)

        assertEquals(setOf(7L, 9L), trafficRefreshProfileIds(previous, next))
        assertEquals(setOf(9L), trafficRefreshProfileIds(next, next))
        assertEquals(emptySet<Long>(), trafficRefreshProfileIds(null, RuntimeTrafficSnapshot.unavailable()))
    }

    @Test
    fun automaticSelectionStatusWinsOnlyForMatchingUnexpiredProfile() {
        val status = AutoNodeSelectionStatus(
            profileId = 7L,
            phase = AutoNodeSelectionPhase.SWITCHED,
            latencyMs = 25,
            until = 5_000L,
        )

        assertEquals(
            HomeConnectionPresentation.AutoSwitched(25),
            HomeConnectionPresentationResolver.resolve(
                input(state = ConnectionState.Connected, automaticSelection = status),
                nowMillis = 4_999L,
                nowElapsedRealtime = 0L,
            ),
        )
        assertEquals(
            HomeConnectionPresentation.Connected(null),
            HomeConnectionPresentationResolver.resolve(
                input(state = ConnectionState.Connected, automaticSelection = status),
                nowMillis = 5_000L,
                nowElapsedRealtime = 0L,
            ),
        )
    }

    @Test
    fun unrelatedProfileHasNoConnectionPresentation() {
        assertNull(
            HomeConnectionPresentationResolver.resolve(
                input(
                    profileId = 8L,
                    selectedProfileId = 7L,
                    currentProfileId = 7L,
                    state = ConnectionState.Connecting,
                ),
                nowMillis = 0L,
                nowElapsedRealtime = 0L,
            ),
        )
    }

    private fun input(
        profileId: Long = 7L,
        selectedProfileId: Long = 7L,
        currentProfileId: Long = 7L,
        state: ConnectionState,
        nodeTestStatus: Int = 0,
        lastError: String = "",
        lastErrorProfileId: Long = 0L,
        lastErrorAtMillis: Long = 0L,
        automaticSelection: AutoNodeSelectionStatus? = null,
        traffic: RuntimeTrafficSnapshot? = null,
    ) = HomeConnectionInput(
        profileId = profileId,
        selectedProfileId = selectedProfileId,
        currentProfileId = currentProfileId,
        state = state,
        nodeTestStatus = nodeTestStatus,
        lastError = lastError,
        lastErrorProfileId = lastErrorProfileId,
        lastErrorAtMillis = lastErrorAtMillis,
        automaticSelection = automaticSelection,
        traffic = traffic,
    )
}
