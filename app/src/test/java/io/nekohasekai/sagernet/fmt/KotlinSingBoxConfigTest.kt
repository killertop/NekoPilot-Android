package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.DEFAULT_TUN_MTU
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinSingBoxConfigTest {

    private companion object {
        const val TEST_MIXED_USERNAME = "test-mixed-user"
        const val TEST_MIXED_PASSWORD = "test-mixed-password"
    }

    private fun defaultChinaRules(): List<KotlinRouteRule> = listOf(
        KotlinRouteRule(id = 1L, domains = "rule_set:geosite-cn", outbound = -1L),
        KotlinRouteRule(id = 2L, ip = "rule_set:geoip-cn", outbound = -1L),
    )

    private fun JSONObject.ruleSetTags(): List<String> = optJSONArray("rule_set")
        ?.let { ruleSets -> List(ruleSets.length()) { index -> ruleSets.getJSONObject(index).getString("tag") } }
        .orEmpty()

    private fun org.json.JSONArray.hasRuleSet(tag: String): Boolean = (0 until length()).any { index ->
        val rule = optJSONObject(index) ?: return@any false
        val tags = rule.optJSONArray("rule_set") ?: return@any false
        (0 until tags.length()).any { tags.optString(it) == tag }
    }

    private fun JSONObject.inbound(tag: String): JSONObject = (0 until getJSONArray("inbounds").length())
        .map { getJSONArray("inbounds").getJSONObject(it) }
        .single { it.getString("tag") == tag }

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
                mixedUsername = TEST_MIXED_USERNAME,
                mixedPassword = TEST_MIXED_PASSWORD,
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
                mixedUsername = TEST_MIXED_USERNAME,
                mixedPassword = TEST_MIXED_PASSWORD,
                routeRules = defaultChinaRules(),
                ruleAssetDirectory = "/data/user/0/io.nekohasekai.sagernet/files",
            ),
        ))

        assertEquals("vless", config.getJSONArray("outbounds").getJSONObject(0).getString("type"))
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertEquals("mixed", config.getJSONArray("inbounds").getJSONObject(0).getString("stack"))
        assertEquals(DEFAULT_TUN_MTU, config.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertTrue(config.getJSONObject("route").getJSONArray("rule_set").length() == 2)

        val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
        assertEquals("dns-system", dnsRules.getJSONObject(0).getJSONArray("preferred_by").getString(0))
        assertEquals("dns-system", dnsRules.getJSONObject(0).getString("server"))
        assertEquals("local", dnsRules.getJSONObject(1).getJSONArray("domain_suffix").getString(0))
        assertEquals("home.arpa", dnsRules.getJSONObject(1).getJSONArray("domain_suffix").getString(4))
        assertEquals("route", dnsRules.getJSONObject(2).getString("action"))
        assertEquals("dns-direct", dnsRules.getJSONObject(2).getString("server"))
        assertEquals("geosite-cn", dnsRules.getJSONObject(2).getJSONArray("rule_set").getString(0))
        assertEquals("route", dnsRules.getJSONObject(3).getString("action"))
        assertEquals("dns-remote", dnsRules.getJSONObject(3).getString("server"))
        val dns = config.getJSONObject("dns")
        assertEquals("5s", dns.getString("timeout"))
        assertTrue(dns.getJSONObject("optimistic").getBoolean("enabled"))
        assertEquals("local", dns.getJSONArray("servers").getJSONObject(3).getString("type"))
    }

    @Test
    fun disabledChinaDefaultsAreNotInjectedIntoVpnConfig() {
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = SOCKSBean().apply {
                    serverAddress = "edge.example"
                    serverPort = 1080
                },
                useVpn = true,
                mixedUsername = TEST_MIXED_USERNAME,
                mixedPassword = TEST_MIXED_PASSWORD,
                routeRules = emptyList(),
                ruleAssetDirectory = "/rules",
            ),
        ))

        val route = config.getJSONObject("route")
        assertFalse(route.has("rule_set"))
        assertFalse(route.getJSONArray("rules").hasRuleSet("geosite-cn"))
        assertFalse(route.getJSONArray("rules").hasRuleSet("geoip-cn"))

        val dns = config.getJSONObject("dns")
        assertFalse(dns.getJSONArray("rules").hasRuleSet("geosite-cn"))
        assertEquals("dns-remote", dns.getString("final"))
        assertTrue((0 until dns.getJSONArray("servers").length()).any { index ->
            dns.getJSONArray("servers").getJSONObject(index).optString("tag") == "dns-direct"
        })
    }

    @Test
    fun privateHealthInboundPrecedesUserDirectRouteAndDnsRules() {
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = SOCKSBean().apply {
                    serverAddress = "edge.example"
                    serverPort = 1080
                },
                useVpn = true,
                healthCheckPort = 20_881,
                mixedUsername = "health-user",
                mixedPassword = "health-password",
                routeRules = listOf(
                    KotlinRouteRule(
                        id = 7L,
                        domains = "full:probe.example",
                        outbound = -1L,
                    ),
                ),
                ruleAssetDirectory = "/rules",
            ),
        ))

        val healthInbound = (0 until config.getJSONArray("inbounds").length())
            .map { config.getJSONArray("inbounds").getJSONObject(it) }
            .single { it.optString("tag") == "health-in" }
        assertEquals("127.0.0.1", healthInbound.getString("listen"))
        assertEquals(20_881, healthInbound.getInt("listen_port"))
        assertEquals("health-user", healthInbound.getJSONArray("users")
            .getJSONObject(0).getString("username"))

        val routeRules = config.getJSONObject("route").getJSONArray("rules")
        val healthRouteIndex = (0 until routeRules.length()).first { index ->
            routeRules.getJSONObject(index).optJSONArray("inbound")?.optString(0) == "health-in"
        }
        val userDirectRouteIndex = (0 until routeRules.length()).first { index ->
            routeRules.getJSONObject(index).optJSONArray("domain")?.optString(0) == "probe.example"
        }
        assertEquals("proxy", routeRules.getJSONObject(healthRouteIndex).getString("outbound"))
        assertTrue(healthRouteIndex < userDirectRouteIndex)

        val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
        val healthDnsIndex = (0 until dnsRules.length()).first { index ->
            dnsRules.getJSONObject(index).optJSONArray("inbound")?.optString(0) == "health-in"
        }
        val userDirectDnsIndex = (0 until dnsRules.length()).first { index ->
            dnsRules.getJSONObject(index).optJSONArray("domain")?.optString(0) == "probe.example"
        }
        assertEquals("dns-remote", dnsRules.getJSONObject(healthDnsIndex).getString("server"))
        assertTrue(healthDnsIndex < userDirectDnsIndex)
    }

    @Test
    fun chinaDomainAndIpDefaultsRemainIndependentlySwitchable() {
        val selected = SOCKSBean().apply {
            serverAddress = "edge.example"
            serverPort = 1080
        }
        val domainOnly = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
            selected = selected,
            useVpn = true,
            mixedUsername = TEST_MIXED_USERNAME,
            mixedPassword = TEST_MIXED_PASSWORD,
            routeRules = listOf(defaultChinaRules().first()),
                ruleAssetDirectory = "/rules",
            ),
        ))
        val ipOnly = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
            selected = selected,
            useVpn = true,
            mixedUsername = TEST_MIXED_USERNAME,
            mixedPassword = TEST_MIXED_PASSWORD,
            routeRules = listOf(defaultChinaRules().last()),
                ruleAssetDirectory = "/rules",
            ),
        ))

        assertEquals(listOf("geosite-cn"), domainOnly.getJSONObject("route").ruleSetTags())
        assertTrue(domainOnly.getJSONObject("dns").getJSONArray("rules").hasRuleSet("geosite-cn"))
        assertEquals(listOf("geoip-cn"), ipOnly.getJSONObject("route").ruleSetTags())
        assertFalse(ipOnly.getJSONObject("dns").getJSONArray("rules").hasRuleSet("geosite-cn"))
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
                mixedUsername = TEST_MIXED_USERNAME,
                mixedPassword = TEST_MIXED_PASSWORD,
                ruleAssetDirectory = "/device-specific/rules",
            ),
        ))

        assertEquals("mixed", config.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertFalse(config.getJSONObject("route").has("rule_set"))
        assertEquals("dns-remote", config.getJSONObject("dns").getString("final"))
        assertEquals("dns-system", config.getJSONObject("route").getString("default_domain_resolver"))
        val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
        assertEquals("dns-system", dnsRules.getJSONObject(0).getString("server"))
        assertEquals("lan", dnsRules.getJSONObject(1).getJSONArray("domain_suffix").getString(1))
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
                mixedUsername = TEST_MIXED_USERNAME,
                mixedPassword = TEST_MIXED_PASSWORD,
                ruleAssetDirectory = "/unused",
            ),
        ))

        val route = config.getJSONObject("route")
        assertFalse(route.getBoolean("auto_detect_interface"))
        assertEquals("dns-system", route.getString("default_domain_resolver"))
        assertEquals("direct", route.getJSONArray("rules").getJSONObject(0).getString("action"))

        val servers = config.getJSONObject("dns").getJSONArray("servers")
        val bootstrap = servers.getJSONObject(0)
        assertEquals("dns-bootstrap", bootstrap.getString("tag"))
        assertEquals("223.5.5.5", bootstrap.getString("server"))
        assertEquals("dns-system", servers.getJSONObject(1).getString("domain_resolver"))
        assertEquals("local", servers.getJSONObject(3).getString("type"))
    }

    @Test
    fun defaultLoopbackMixedInboundIsAuthenticated() {
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = SOCKSBean().apply {
                    serverAddress = "edge.example"
                    serverPort = 1080
                },
                useVpn = false,
                mixedUsername = "loopback-user",
                mixedPassword = "loopback-password",
                ruleAssetDirectory = "/rules",
            ),
        ))

        val mixedInbound = config.inbound("mixed-in")
        assertEquals("127.0.0.1", mixedInbound.getString("listen"))
        assertEquals("loopback-user", mixedInbound.getJSONArray("users")
            .getJSONObject(0).getString("username"))
        assertEquals("loopback-password", mixedInbound.getJSONArray("users")
            .getJSONObject(0).getString("password"))
    }

    @Test
    fun lanMixedInboundIsAuthenticated() {
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = SOCKSBean().apply {
                    serverAddress = "edge.example"
                    serverPort = 1080
                },
                useVpn = false,
                allowAccess = true,
                mixedUsername = "lan-user",
                mixedPassword = "lan-password",
                ruleAssetDirectory = "/rules",
            ),
        ))

        val mixedInbound = config.inbound("mixed-in")
        assertEquals("0.0.0.0", mixedInbound.getString("listen"))
        assertEquals("lan-user", mixedInbound.getJSONArray("users")
            .getJSONObject(0).getString("username"))
        assertEquals("lan-password", mixedInbound.getJSONArray("users")
            .getJSONObject(0).getString("password"))
    }

    @Test
    fun mixedInboundRejectsMissingCredentialsForLoopbackLanAndNodeTests() {
        val failures = listOf(
            false to false,
            true to false,
            true to true,
        ).map { (allowAccess, forTest) ->
            runCatching {
                buildKotlinSingBoxConfig(
                    KotlinSingBoxConfigInput(
                        selected = SOCKSBean().apply {
                            serverAddress = "edge.example"
                            serverPort = 1080
                        },
                        useVpn = false,
                        allowAccess = allowAccess,
                        forTest = forTest,
                        mixedUsername = "user",
                        mixedPassword = "",
                        ruleAssetDirectory = "/rules",
                    ),
                )
            }.exceptionOrNull()
        }

        assertTrue(failures.all { it is IllegalArgumentException })
    }

    @Test
    fun routesWireGuardThroughAnEndpointInsteadOfTheRemovedLegacyOutbound() {
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = WireGuardBean().apply {
                    serverAddress = "wg.example"
                    serverPort = 51_820
                    localAddress = "10.0.0.2/32"
                    privateKey = "K6ZFGQDIo1EPPoojWjum/bceCyqPDcPXLFJfdRnT+8g="
                    peerPublicKey = "Ck1+A0XjAre3etA8bCxrKZ+agz/y2RO7CvbRNMo5tCE="
                },
                useVpn = true,
                mixedUsername = TEST_MIXED_USERNAME,
                mixedPassword = TEST_MIXED_PASSWORD,
                ruleAssetDirectory = "/rules",
            ),
        ))

        assertEquals(1, config.getJSONArray("outbounds").length())
        assertEquals("direct", config.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        val endpoint = config.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wireguard", endpoint.getString("type"))
        assertEquals("proxy", endpoint.getString("tag"))
        assertEquals("wg.example", endpoint.getJSONArray("peers").getJSONObject(0).getString("address"))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
    }

    @Test
    fun automaticSelectorCanIncludeWireGuardEndpointCandidates() {
        val socks = SOCKSBean().apply {
            serverAddress = "socks.example"
            serverPort = 1080
        }
        val wireGuard = WireGuardBean().apply {
            serverAddress = "wg.example"
            serverPort = 51_820
            localAddress = "10.0.0.2/32"
            privateKey = "K6ZFGQDIo1EPPoojWjum/bceCyqPDcPXLFJfdRnT+8g="
            peerPublicKey = "Ck1+A0XjAre3etA8bCxrKZ+agz/y2RO7CvbRNMo5tCE="
        }
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = wireGuard,
                selectedProfileId = 22L,
                selectorNodes = listOf(
                    KotlinSelectorNode(11L, socks),
                    KotlinSelectorNode(22L, wireGuard),
                ),
                useVpn = true,
                mixedUsername = TEST_MIXED_USERNAME,
                mixedPassword = TEST_MIXED_PASSWORD,
                ruleAssetDirectory = "/rules",
            ),
        ))

        val selector = config.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("selector", selector.getString("type"))
        assertEquals("node-22", selector.getString("default"))
        assertEquals("node-22", selector.getJSONArray("outbounds").getString(1))
        assertEquals("node-22", config.getJSONArray("endpoints").getJSONObject(0).getString("tag"))
    }

    @Test
    fun compilesEnabledUserRouteRulesBeforeBuiltInFallbacks() {
        val config = JSONObject(buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = SOCKSBean().apply {
                    serverAddress = "edge.example"
                    serverPort = 1080
                },
                useVpn = true,
                mixedUsername = TEST_MIXED_USERNAME,
                mixedPassword = TEST_MIXED_PASSWORD,
                routeRules = listOf(
                    KotlinRouteRule(
                        id = 42L,
                        domains = "full:api.example.com\ndomain:example.org",
                        ip = "private",
                        port = "443,8443:8444",
                        sourcePort = "53000",
                        network = "tcp\nudp",
                        source = "10.0.0.0/8",
                        protocol = "tls",
                        outbound = -1L,
                        userIds = listOf(10001),
                    ),
                ),
                ruleAssetDirectory = "/rules",
            ),
        ))

        val routeRules = config.getJSONObject("route").getJSONArray("rules")
        val userRule = routeRules.getJSONObject(2)
        assertEquals("direct", userRule.getString("outbound"))
        assertEquals("api.example.com", userRule.getJSONArray("domain").getString(0))
        assertEquals("example.org", userRule.getJSONArray("domain_suffix").getString(0))
        assertTrue(userRule.getBoolean("ip_is_private"))
        assertEquals(443, userRule.getJSONArray("port").getInt(0))
        assertEquals("8443:8444", userRule.getJSONArray("port_range").getString(0))
        assertEquals(10001, userRule.getJSONArray("user_id").getInt(0))

        val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
        val userDnsRule = dnsRules.getJSONObject(2)
        assertEquals("dns-direct", userDnsRule.getString("server"))
        assertEquals("api.example.com", userDnsRule.getJSONArray("domain").getString(0))
    }

    @Test
    fun rejectsRulesReferencingAnOutboundOutsideTheRunningSelector() {
        val failure = runCatching {
            buildKotlinSingBoxConfig(
                KotlinSingBoxConfigInput(
                    selected = SOCKSBean().apply {
                        serverAddress = "edge.example"
                        serverPort = 1080
                    },
                    useVpn = true,
                    mixedUsername = TEST_MIXED_USERNAME,
                    mixedPassword = TEST_MIXED_PASSWORD,
                    routeRules = listOf(
                        KotlinRouteRule(
                            id = 7L,
                            domains = "example.com",
                            outbound = 999L,
                        ),
                    ),
                    ruleAssetDirectory = "/rules",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
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
            KotlinNodeTestRoute(
                first,
                "test-in-0",
                "test-node-0",
                20_881,
                "test-user-0",
                "test-password-0",
            ),
            KotlinNodeTestRoute(
                second,
                "test-in-1",
                "test-node-1",
                20_882,
                "test-user-1",
                "test-password-1",
            ),
        )))

        assertEquals(2, config.getJSONArray("inbounds").length())
        assertEquals(3, config.getJSONArray("outbounds").length())
        assertTrue(config.getJSONObject("route").getBoolean("auto_detect_interface"))
        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("test-in-0", rules.getJSONObject(1).getJSONArray("inbound").getString(0))
        assertEquals("test-node-0", rules.getJSONObject(1).getString("outbound"))
        assertEquals(
            "test-user-0",
            config.getJSONArray("inbounds").getJSONObject(0)
                .getJSONArray("users").getJSONObject(0).getString("username"),
        )
        assertEquals("test-in-1", rules.getJSONObject(2).getJSONArray("inbound").getString(0))
        assertEquals("test-node-1", rules.getJSONObject(2).getString("outbound"))
        assertEquals("direct", config.getJSONObject("route").getString("final"))
        assertEquals("dns-system", config.getJSONObject("route").getString("default_domain_resolver"))
        assertEquals("5s", config.getJSONObject("dns").getString("timeout"))
        assertEquals("local", config.getJSONObject("dns").getJSONArray("servers")
            .getJSONObject(1).getString("type"))
    }
}
