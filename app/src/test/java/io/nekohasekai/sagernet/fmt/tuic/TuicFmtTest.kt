package io.nekohasekai.sagernet.fmt.tuic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuicFmtTest {
    private val uuid = "2dd61d93-75d8-4da4-ac0e-6aece7eac365"

    @Test
    fun parsesTuicV5OnlyAndCanonicalizesRuntimeSettings() {
        val profile = parseTuic(
            "TUIC://$uuid:password@tuic.example:443/?sni=edge.example&" +
                "congestion_control=BBR&udp_relay_mode=QUIC&zero_rtt_handshake=true#Edge",
        )

        assertEquals(5, profile.protocolVersion)
        assertEquals(uuid, profile.uuid)
        assertEquals("password", profile.token)
        assertEquals("bbr", profile.congestionController)
        assertEquals("quic", profile.udpRelayMode)
        assertTrue(profile.reduceRTT)
    }

    @Test
    fun rejectsUnsupportedOrLossyTuicUriFeatures() {
        listOf(
            "tuic://$uuid:password@example.com/?udp_over_stream=true",
            "tuic://$uuid:password@example.com/?heartbeat=10s",
            "tuic://$uuid:password@example.com/?network=tcp",
            "tuic://$uuid:password@example.com/?congestion_control=reno",
            "tuic://$uuid:password@example.com/?udp_relay_mode=stream",
            "tuic://$uuid:password@example.com/?insecure=maybe",
            "tuic://$uuid:password@example.com/?sni=one&sni=two",
            "tuic://not-a-uuid:password@example.com/",
            "tuic://$uuid@example.com/",
        ).forEach { link ->
            val failure = runCatching { parseTuic(link) }.exceptionOrNull()
            assertTrue("expected $link to be rejected", failure is IllegalArgumentException || failure is IllegalStateException)
        }
    }
}
