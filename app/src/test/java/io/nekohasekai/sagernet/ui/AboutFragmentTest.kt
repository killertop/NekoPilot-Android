package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutFragmentTest {

    @Test
    fun coreVersionCapabilitiesAreReadableAndBounded() {
        val raw = """
            sing-box: 1.13.14-neko-1
            go1.26.5@android/arm64
            with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls
        """.trimIndent()

        assertEquals(
            """
                sing-box: 1.13.14-neko-1
                go1.26.5@android/arm64
                conntrack · gvisor · quic
                wireguard · utls
            """.trimIndent(),
            formatCoreVersion(raw),
        )
    }

    @Test
    fun unknownVersionShapeIsPreserved() {
        assertEquals("sing-box unavailable", formatCoreVersion("sing-box unavailable"))
    }

    @Test
    fun coreVersionSummaryShowsOnlyTheUserFacingVersion() {
        assertEquals(
            "1.13.14-neko-1",
            formatCoreVersionSummary("sing-box: 1.13.14-neko-1\ngo1.26.5@android/arm64"),
        )
        assertEquals("sing-box unavailable", formatCoreVersionSummary("sing-box unavailable"))
    }
}
