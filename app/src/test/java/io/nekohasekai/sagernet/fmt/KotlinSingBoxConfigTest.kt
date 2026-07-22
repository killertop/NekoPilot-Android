package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinSingBoxConfigTest {

    @Test
    fun automaticSelectorKeepsExistingConnectionsAndUsesSelectedDefault() {
        val selected = SOCKSBean().apply {
            serverAddress = "one.example"
            serverPort = 1080
        }
        val candidate = SOCKSBean().apply {
            serverAddress = "two.example"
            serverPort = 1080
        }
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = selected,
                selectedProfileId = 11L,
                selectorNodes = listOf(
                    KotlinSelectorNode(11L, selected),
                    KotlinSelectorNode(22L, candidate),
                ),
                useVpn = true,
                ruleAssetDirectory = "/rules",
            )
        ))

        val outbounds = config.getJSONArray("outbounds")
        val selector = outbounds.getJSONObject(2)
        assertEquals("selector", selector.getString("type"))
        assertEquals("proxy", selector.getString("tag"))
        assertEquals("node-11", selector.getString("default"))
        assertEquals(false, selector.getBoolean("interrupt_exist_connections"))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
    }
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

    @Test
    fun nonVpnConfigDoesNotContainTunOrLocalRuleAssetPaths() {
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = VMessBean().apply {
                    serverAddress = "edge.example"
                    serverPort = 443
                    uuid = "11111111-1111-1111-1111-111111111111"
                    alterId = -1
                },
                useVpn = false,
                ruleAssetDirectory = "/device-specific/rules",
            ),
        ))

        assertEquals("mixed", config.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertFalse(config.getJSONObject("route").has("rule_set"))
        assertEquals("dns-remote", config.getJSONObject("dns").getString("final"))
    }

    @Test
    fun nodeTestConfigUsesBootstrapDnsAndDefaultAndroidNetwork() {
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = VMessBean().apply {
                    serverAddress = "edge.example"
                    serverPort = 443
                    uuid = "11111111-1111-1111-1111-111111111111"
                    alterId = -1
                },
                useVpn = false,
                forTest = true,
                ruleAssetDirectory = "/unused",
            ),
        ))

        val route = config.getJSONObject("route")
        assertFalse(route.getBoolean("auto_detect_interface"))
        assertEquals("dns-bootstrap", route.getString("default_domain_resolver"))
        assertEquals("direct", route.getJSONArray("rules").getJSONObject(0).getString("action"))

        val servers = config.getJSONObject("dns").getJSONArray("servers")
        val bootstrap = servers.getJSONObject(0)
        assertEquals("dns-bootstrap", bootstrap.getString("tag"))
        assertEquals("223.5.5.5", bootstrap.getString("server"))
        assertEquals("dns-bootstrap", servers.getJSONObject(1).getString("domain_resolver"))
    }
}
