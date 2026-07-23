package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeTestStatusResolverTest {
    @Test
    fun runningUntestedNodeIsTesting() {
        assertEquals(
            NodeTestDisplayState.Testing,
            NodeTestStatusResolver.resolve(true, snapshot(status = 0)),
        )
    }

    @Test
    fun availableResultKeepsLatencyAndDownloadSpeed() {
        assertEquals(
            NodeTestDisplayState.Available(123, 45.6),
            NodeTestStatusResolver.resolve(
                false,
                snapshot(status = 1, latencyMs = 123, downloadMbps = 45.6),
            ),
        )
    }

    @Test
    fun failureKindsKeepTheirExistingMessagePolicy() {
        assertEquals(
            NodeTestDisplayState.Unavailable("raw", translateFriendlyMessage = false),
            NodeTestStatusResolver.resolve(false, snapshot(status = 2, error = "raw")),
        )
        assertEquals(
            NodeTestDisplayState.Unavailable("core", translateFriendlyMessage = true),
            NodeTestStatusResolver.resolve(false, snapshot(status = 3, error = "core")),
        )
    }

    @Test
    fun unknownStatusHasNoDisplayState() {
        assertNull(NodeTestStatusResolver.resolve(false, snapshot(status = 99)))
    }

    private fun snapshot(
        status: Int,
        latencyMs: Int = 0,
        downloadMbps: Double? = null,
        error: String? = null,
    ) = NodeTestSnapshot(status, latencyMs, downloadMbps, error)
}
