package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupUpdaterWorkerTest {
    @Test
    fun largeSubscriptionResolutionKeepsWorkerCountBounded() {
        assertEquals(0, GroupUpdater.resolveWorkerCount(0))
        assertEquals(1, GroupUpdater.resolveWorkerCount(1))
        assertEquals(5, GroupUpdater.resolveWorkerCount(5))
        assertEquals(5, GroupUpdater.resolveWorkerCount(10_000))
    }
}
