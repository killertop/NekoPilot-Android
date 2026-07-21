package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinSingBoxConfigTest {
    @Test
    fun buildsOneNodeVpnConfigWithNativeRuleSets() {
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = VMessBean().apply {
                    serverAddress = "edge.example"
                    serverPort = 443
                    uuid = "11111111-1111-1111-1111-111111111111"
                    alterId = -1
                },
                useVpn = true,
                ruleAssetDirectory = "/data/user/0/io.nekohasekai.sagernet/files",
            ),
        ))

        assertEquals("vless", config.getJSONArray("outbounds").getJSONObject(0).getString("type"))
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertTrue(config.getJSONObject("route").getJSONArray("rule_set").length() == 2)
    }
}
