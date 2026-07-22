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
                proxyTag = "proxy-session-test",
                useVpn = true,
                ruleAssetDirectory = "/rules",
            )
        ))

        val outbounds = config.getJSONArray("outbounds")
        val selector = outbounds.getJSONObject(2)
        assertEquals("selector", selector.getString("type"))
        assertEquals("proxy-session-test", selector.getString("tag"))
        assertEquals("node-11", selector.getString("default"))
        assertEquals(false, selector.getBoolean("interrupt_exist_connections"))
        assertEquals(4, outbounds.length())
        assertEquals("proxy-session-test", config.getJSONObject("route").getString("final"))
        assertEquals(
            "proxy-session-test",
            config.getJSONObject("dns").getJSONArray("servers")
                .getJSONObject(1).getString("detour"),
        )
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
        assertEquals("mixed", config.getJSONArray("inbounds").getJSONObject(0).getString("stack"))
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

    @Test
    fun batchNodeTestConfigRoutesEveryInboundToItsOwnOutbound() {
        val first = SOCKSBean().apply {
            serverAddress = "one.example"
            serverPort = 1080
        }
        val second = SOCKSBean().apply {
            serverAddress = "two.example"
            serverPort = 1080
        }
        val config = JSONObject(buildKotlinNodeTestConfig(listOf(
            KotlinNodeTestRoute(first, "test-in-0", "test-node-0", 20_881),
            KotlinNodeTestRoute(second, "test-in-1", "test-node-1", 20_882),
        )))

        assertEquals(2, config.getJSONArray("inbounds").length())
        assertEquals(3, config.getJSONArray("outbounds").length())
        assertTrue(config.getJSONObject("route").getBoolean("auto_detect_interface"))
        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("test-in-0", rules.getJSONObject(1).getJSONArray("inbound").getString(0))
        assertEquals("test-node-0", rules.getJSONObject(1).getString("outbound"))
        assertEquals("test-in-1", rules.getJSONObject(2).getJSONArray("inbound").getString(0))
        assertEquals("test-node-1", rules.getJSONObject(2).getString("outbound"))
        assertEquals("direct", config.getJSONObject("route").getString("final"))
    }
}
