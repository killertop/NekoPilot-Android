package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.assertEquals
import org.junit.Test

class V2RayFmtTest {
    @Test
    fun ignoresMalformedWebSocketEarlyData() {
        val bean = parseTrojan(
            "trojan://secret@example.com:443?type=ws&ed=not-a-number&eh=Sec-WebSocket-Protocol"
        )

        bean.applyDefaultValues()
        assertEquals(0, bean.wsMaxEarlyData)
        assertEquals("", bean.earlyDataHeaderName)
    }
}
