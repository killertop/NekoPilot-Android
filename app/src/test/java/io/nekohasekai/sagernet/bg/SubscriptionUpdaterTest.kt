package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubscriptionUpdaterTest {

    @Test
    fun schedulesFirstRunAtEarliestDueSubscription() {
        val schedule = calculateSubscriptionSchedule(
            nowSeconds = 10_000,
            timings = listOf(
                SubscriptionTiming(lastUpdatedSeconds = 10_000, intervalMinutes = 60),
                SubscriptionTiming(lastUpdatedSeconds = 2_900, intervalMinutes = 120),
            ),
        )

        assertEquals(60L, schedule.repeatIntervalMinutes)
        assertEquals(100L, schedule.initialDelaySeconds)
    }

    @Test
    fun overdueSubscriptionRunsWithoutExtraDelay() {
        val schedule = calculateSubscriptionSchedule(
            nowSeconds = 10_000,
            timings = listOf(
                SubscriptionTiming(lastUpdatedSeconds = 1_000, intervalMinutes = 60),
            ),
        )

        assertEquals(0L, schedule.initialDelaySeconds)
    }

    @Test
    fun enforcesWorkManagerMinimumPeriodicInterval() {
        val schedule = calculateSubscriptionSchedule(
            nowSeconds = 1_000,
            timings = listOf(
                SubscriptionTiming(lastUpdatedSeconds = 900, intervalMinutes = 1),
            ),
        )

        assertEquals(15L, schedule.repeatIntervalMinutes)
        assertEquals(800L, schedule.initialDelaySeconds)
    }

    @Test
    fun rejectsEmptySchedule() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateSubscriptionSchedule(0, emptyList())
        }
    }
}
