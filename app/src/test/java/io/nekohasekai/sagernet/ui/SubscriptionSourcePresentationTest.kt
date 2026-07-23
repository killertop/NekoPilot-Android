package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionSourcePresentationTest {
    private val now = 1_000_000L

    @Test
    fun updatingOverridesEveryTimestamp() {
        assertEquals(
            SubscriptionUpdateState.Updating,
            row(lastUpdatedSeconds = 999L, updating = true).updateState(now),
        )
    }

    @Test
    fun missingTimestampIsNeverUpdated() {
        assertEquals(
            SubscriptionUpdateState.NeverUpdated,
            row(lastUpdatedSeconds = 0L).updateState(now),
        )
    }

    @Test
    fun recentAndMinuteOldUpdatesKeepExistingBoundary() {
        assertEquals(
            SubscriptionUpdateState.JustUpdated,
            row(lastUpdatedSeconds = 941L).updateState(now),
        )
        assertEquals(
            SubscriptionUpdateState.UpdatedAt(940_000L),
            row(lastUpdatedSeconds = 940L).updateState(now),
        )
    }

    @Test
    fun deletionCheckObservesUpdateThatStartsAfterConfirmation() {
        val updatingGroupIds = mutableSetOf<Long>()

        assertEquals(false, isSubscriptionUpdating(7L, updatingGroupIds))

        updatingGroupIds += 7L

        assertEquals(true, isSubscriptionUpdating(7L, updatingGroupIds))
    }

    private fun row(
        lastUpdatedSeconds: Long,
        updating: Boolean = false,
    ) = SubscriptionSourceRow(
        groupId = 7L,
        displayName = "Airport",
        nodeCount = 3,
        lastUpdatedSeconds = lastUpdatedSeconds,
        updating = updating,
    )
}
