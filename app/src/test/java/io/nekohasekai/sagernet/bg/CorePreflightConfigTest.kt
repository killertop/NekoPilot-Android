package io.nekohasekai.sagernet.bg

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class CorePreflightConfigTest {
    @Test
    fun acceptsOneLoopbackMixedInboundWithoutTun() {
        assertIsolatedPreflightConfig(
            JSONObject().put("inbounds", JSONArray().put(
                JSONObject()
                    .put("type", "mixed")
                    .put("listen", "127.0.0.1")
                    .put("listen_port", 31_001),
            )).toString(),
            31_001,
        )
    }

    @Test
    fun rejectsTunBeforeTheCandidateCanTouchVpnState() {
        val config = JSONObject().put("inbounds", JSONArray().put(JSONObject().put("type", "tun")))

        assertThrows(IllegalStateException::class.java) {
            assertIsolatedPreflightConfig(config.toString(), 31_002)
        }
    }

    @Test
    fun rejectsAnySecondInboundEvenWhenTheMixedInboundIsSafe() {
        val config = JSONObject().put("inbounds", JSONArray()
            .put(
                JSONObject()
                    .put("type", "mixed")
                    .put("listen", "127.0.0.1")
                    .put("listen_port", 31_002),
            )
            .put(
                JSONObject()
                    .put("type", "socks")
                    .put("listen", "0.0.0.0")
                    .put("listen_port", 31_005),
            ))

        assertThrows(IllegalStateException::class.java) {
            assertIsolatedPreflightConfig(config.toString(), 31_002)
        }
    }

    @Test
    fun rejectsPublicListenerAndPortMismatch() {
        val publicListener = JSONObject().put("inbounds", JSONArray().put(
            JSONObject()
                .put("type", "mixed")
                .put("listen", "0.0.0.0")
                .put("listen_port", 31_003),
        ))
        val mismatchedPort = JSONObject().put("inbounds", JSONArray().put(
            JSONObject()
                .put("type", "mixed")
                .put("listen", "127.0.0.1")
                .put("listen_port", 31_004),
        ))

        assertThrows(IllegalStateException::class.java) {
            assertIsolatedPreflightConfig(publicListener.toString(), 31_003)
        }
        assertThrows(IllegalStateException::class.java) {
            assertIsolatedPreflightConfig(mismatchedPort.toString(), 31_003)
        }
    }
}
