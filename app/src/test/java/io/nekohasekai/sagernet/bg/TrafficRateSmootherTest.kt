package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficRateSmootherTest {
    @Test
    fun `smooths one-second samples and resets without carrying stale traffic`() {
        val smoother = TrafficRateSmoother(newSampleWeight = 0.4)

        assertEquals(1_000L to 2_000L, smoother.update(1_000L, 2_000L))
        assertEquals(1_400L to 1_200L, smoother.update(2_000L, 0L))
        assertEquals(840L to 0L, smoother.update(0L, 0L))
        assertEquals(0L to 0L, smoother.update(0L, 0L))

        smoother.reset()
        assertEquals(0L to 500L, smoother.update(-10L, 500L))
    }

    @Test
    fun `accumulator includes only the active node and forgets closed connections`() {
        val accumulator = NodeTrafficAccumulator()
        accumulator.reset(activeProfileId = 7L)

        accumulator.recordNew("direct", null, 7L, 100L, 200L, baselineOnly = false)
        accumulator.recordNew("node-7", 7L, 7L, 1_000L, 2_000L, baselineOnly = false)
        accumulator.recordNew("node-8", 8L, 7L, 5_000L, 6_000L, baselineOnly = false)
        accumulator.recordUpdate("node-7", 7L, 250L, 350L, closed = false)
        assertEquals(1_250L to 2_350L, accumulator.sample(7L))
        assertEquals(0L to 0L, accumulator.sample(7L))

        accumulator.recordUpdate("node-7", 8L, 100L, 100L, closed = false)
        accumulator.recordUpdate("node-8", 8L, 400L, 500L, closed = false)
        assertEquals(400L to 500L, accumulator.sample(8L))

        accumulator.recordUpdate("node-8", 8L, 100L, 100L, closed = true)
        assertEquals(100L to 100L, accumulator.sample(8L))
        accumulator.recordUpdate("node-8", 8L, 100L, 100L, closed = false)
        assertEquals(0L to 0L, accumulator.sample(8L))
    }

    @Test
    fun `initial reset snapshot establishes totals without reporting historical traffic`() {
        val accumulator = NodeTrafficAccumulator()
        accumulator.reset(activeProfileId = 7L)

        accumulator.recordNew(
            "existing",
            7L,
            7L,
            uplinkTotal = 1_000L,
            downlinkTotal = 2_000L,
            baselineOnly = true,
        )
        assertEquals(0L to 0L, accumulator.sample(7L))

        accumulator.recordUpdate("existing", 7L, 100L, 200L, closed = false)
        assertEquals(100L to 200L, accumulator.sample(7L))
    }

    @Test
    fun `closed totals recover short connection remainder without double counting updates`() {
        val accumulator = NodeTrafficAccumulator()
        accumulator.reset(activeProfileId = 7L)
        accumulator.recordNew("short", 7L, 7L, 0L, 0L, baselineOnly = false)

        accumulator.recordUpdate("short", 7L, 200L, 300L, closed = false)
        accumulator.recordUpdate(
            "short",
            7L,
            uplinkDelta = 0L,
            downlinkDelta = 0L,
            closed = true,
            finalUplinkTotal = 500L,
            finalDownlinkTotal = 900L,
        )
        // 200/300 came from UPDATE; CLOSED contributes only the 300/600 remainder.
        assertEquals(500L to 900L, accumulator.sample(7L))
    }

    @Test
    fun `closed totals do not count bytes already fully reported by updates`() {
        val accumulator = NodeTrafficAccumulator()
        accumulator.reset(activeProfileId = 7L)
        accumulator.recordNew("complete", 7L, 7L, 0L, 0L, baselineOnly = false)
        accumulator.recordUpdate("complete", 7L, 500L, 900L, closed = false)
        accumulator.recordUpdate(
            "complete",
            7L,
            uplinkDelta = 0L,
            downlinkDelta = 0L,
            closed = true,
            finalUplinkTotal = 500L,
            finalDownlinkTotal = 900L,
        )

        assertEquals(500L to 900L, accumulator.sample(7L))
    }

    @Test
    fun `normalizes accumulated bytes using actual elapsed time`() {
        assertEquals(1_000L, bytesPerSecond(bytes = 2_000L, elapsedMs = 2_000L))
        assertEquals(0L, bytesPerSecond(bytes = 2_000L, elapsedMs = 0L))
    }

    @Test
    fun `outbound mapping supports selector leaves and single-node session tags`() {
        assertEquals(42L, profileIdForTrafficOutbound("node-42", "proxy-session", 7L))
        assertEquals(7L, profileIdForTrafficOutbound("proxy-session", "proxy-session", 7L))
        assertNull(profileIdForTrafficOutbound("direct", "proxy-session", 7L))
        assertNull(profileIdForTrafficOutbound("proxy-session", "proxy-session", 0L))
        assertNull(profileIdForTrafficOutbound("node-invalid", "proxy-session", 7L))
    }
}
