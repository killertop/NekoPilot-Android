package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSwitchPolicyTest {
    @Test
    fun choosesLowestPositiveLatencyAcrossGroups() {
        assertEquals(22L, AutoSwitchPolicy.best(mapOf(11L to 180, 22L to 42, 33L to 90)))
    }

    @Test
    fun ignoresFailedMeasurements() {
        assertEquals(3L, AutoSwitchPolicy.best(mapOf(1L to -1, 2L to 0, 3L to 80)))
        assertNull(AutoSwitchPolicy.best(mapOf(1L to -1, 2L to 0)))
    }

    @Test
    fun resolvesEqualLatencyDeterministically() {
        assertEquals(7L, AutoSwitchPolicy.best(mapOf(9L to 50, 7L to 50)))
    }
}
