package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxySelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadSelectionPolicyTest {

    @Test
    fun rejectedDifferentCandidateReturnsToActiveRuntimeSelection() {
        assertTrue(
            shouldRestoreSelectionAfterCandidateFailure(
                candidate = ProxySelection(profileId = 22L, groupId = 3L),
                active = ProxySelection(profileId = 11L, groupId = 3L),
            ),
        )
    }

    @Test
    fun rejectedEditOfActiveProfileKeepsItsSelection() {
        assertFalse(
            shouldRestoreSelectionAfterCandidateFailure(
                candidate = ProxySelection(profileId = 11L, groupId = 3L),
                active = ProxySelection(profileId = 11L, groupId = 3L),
            ),
        )
    }

    @Test
    fun selectedGroupChangeForActiveProfileDoesNotNeedRollback() {
        assertFalse(
            shouldRestoreSelectionAfterCandidateFailure(
                candidate = ProxySelection(profileId = 11L, groupId = 7L),
                active = ProxySelection(profileId = 11L, groupId = 3L),
            ),
        )
    }

    @Test
    fun profileChangedByAutomaticSelectorDoesNotMatchStaleReloadRuntime() {
        val started = ProxyEntity().apply {
            id = 11L
            groupId = 3L
            type = ProxyEntity.TYPE_SOCKS
            configRevision = 4L
        }
        val switched = ProxyEntity().apply {
            id = 22L
            groupId = 3L
            type = ProxyEntity.TYPE_SOCKS
            configRevision = 4L
        }

        assertFalse(runtimeProfileMatches(switched, started))
        assertTrue(runtimeProfileMatches(started, started))
    }
}
