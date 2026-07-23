package io.nekohasekai.sagernet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProjectionModelsTest {
    @Test
    fun automaticSelectionEncodingRemainsBackwardCompatible() {
        val encoded = AutoNodeSelectionStatus(
            profileId = 42L,
            phase = AutoNodeSelectionPhase.SWITCHED,
            latencyMs = 87,
            until = 12_345L,
        ).encode()

        assertEquals(
            AutoNodeSelectionStatus(42L, AutoNodeSelectionPhase.SWITCHED, 87, 12_345L),
            AutoNodeSelectionStatus.decode(encoded),
        )
        assertNull(AutoNodeSelectionStatus.decode("""{"profileId":0,"phase":"FAILED"}"""))
        assertNull(AutoNodeSelectionStatus.decode("not-json"))
    }

    @Test
    fun trafficFreshnessRejectsUnavailableFutureAndExpiredSamples() {
        val snapshot = RuntimeTrafficSnapshot(
            available = true,
            profileId = 42L,
            uplinkBytesPerSecond = 10L,
            downlinkBytesPerSecond = 20L,
            sampledAtElapsedRealtime = 1_000L,
        )

        assertTrue(snapshot.isFresh(nowElapsedRealtime = 3_500L))
        assertFalse(snapshot.isFresh(nowElapsedRealtime = 3_501L))
        assertFalse(snapshot.isFresh(nowElapsedRealtime = 999L))
        assertFalse(RuntimeTrafficSnapshot.unavailable(1_000L).isFresh(1_001L))
    }
}
