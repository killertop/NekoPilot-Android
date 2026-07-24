package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.DEFAULT_TUN_MTU
import org.json.JSONArray
import org.json.JSONObject

private const val DNS_SYSTEM_TAG = "dns-system"
private const val DNS_QUERY_TIMEOUT = "5s"
private const val DNS_OPTIMISTIC_TIMEOUT = "5m"

internal data class KotlinSingBoxConfigInput(
    val selected: AbstractBean,
    val selectedProfileId: Long = 0L,
    val selectorNodes: List<KotlinSelectorNode> = emptyList(),
    val proxyTag: String = "proxy",
    val useVpn: Boolean,
    val tunStack: String = "mixed",
    val mixedPort: Int = 20_880,
    val mixedUsername: String = "",
    val mixedPassword: String = "",
    val allowAccess: Boolean = false,
    val ruleAssetDirectory: String,
    val forTest: Boolean = false,
)

internal data class KotlinSelectorNode(val profileId: Long, val bean: AbstractBean) {
    val tag: String get() = "node-$profileId"
}

internal data class KotlinNodeTestRoute(
    val bean: AbstractBean,
    val inboundTag: String,
    val outboundTag: String,
    val mixedPort: Int,
)

/**
 * Minimal product configuration for one selected node. No chain, plugin, custom JSON, or Clash
 * compatibility is retained: all runtime schema is standard sing-box 1.14 JSON.
 */
internal fun buildKotlinSingBoxConfig(input: KotlinSingBoxConfigInput): String = JSONObject().apply {
    require(input.proxyTag.isNotBlank()) { "Outbound selector tag must not be blank" }
    val includeTun = input.useVpn && !input.forTest
    val selectorNodes = input.selectorNodes.distinctBy(KotlinSelectorNode::profileId)
    val useSelector = selectorNodes.size > 1 &&
        input.selectedProfileId > 0L &&
        selectorNodes.any { it.profileId == input.selectedProfileId }
    put("log", JSONObject().put("level", "warn"))
    put("outbounds", JSONArray().apply {
        if (useSelector) {
            selectorNodes.forEach { node -> put(buildSingBoxOutbound(node.bean, node.tag)) }
            put(JSONObject().apply {
                put("type", "selector")
                put("tag", input.proxyTag)
                put("outbounds", JSONArray(selectorNodes.map(KotlinSelectorNode::tag)))
                put("default", "node-${input.selectedProfileId}")
                // New connections move immediately; established streams keep their original
                // outbound and finish naturally.
                put("interrupt_exist_connections", false)
            })
        } else {
            put(buildSingBoxOutbound(input.selected, input.proxyTag))
        }
        put(JSONObject().put("type", "direct").put("tag", "direct"))
    })
    put("inbounds", JSONArray().apply {
        if (includeTun) {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("stack", input.tunStack)
                put("mtu", DEFAULT_TUN_MTU)
                put("address", JSONArray(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")))
                put("auto_route", true)
                put("dns_mode", "hijack")
            })
        }
        put(JSONObject().apply {
            put("type", "mixed")
            put("tag", "mixed-in")
            put("listen", if (input.allowAccess && !input.forTest) "0.0.0.0" else "127.0.0.1")
            put("listen_port", input.mixedPort)
            if (!input.forTest && (input.mixedUsername.isNotBlank() || input.mixedPassword.isNotBlank())) {
                put("users", JSONArray().put(JSONObject().apply {
                    put("username", input.mixedUsername)
                    put("password", input.mixedPassword)
                }))
            }
        })
    })
    put("route", JSONObject().apply {
        // The VPN service protects its outbound sockets through Android's VPN API. A temporary
        // node-test core has no TUN/upstream binding, so forcing interface auto-detection there
        // leaves protocol outbounds with no usable network interface.
        put("auto_detect_interface", !input.forTest)
        // Resolve endpoint names through Android's physical-network resolver. The explicit DoH
        // server remains available for user DNS, but a filtered DoH bootstrap must not prevent
        // the proxy endpoint itself from being resolved.
        put("default_domain_resolver", DNS_SYSTEM_TAG)
        if (includeTun) put("rule_set", JSONArray().apply {
            put(localRuleSet("geosite-cn", "geosite-cn.srs", input.ruleAssetDirectory))
            put(localRuleSet("geoip-cn", "geoip-cn.srs", input.ruleAssetDirectory))
        })
        put("rules", JSONArray().apply {
            // The bootstrap resolver must stay direct; otherwise it would need the proxy
            // before it can resolve the proxy endpoint itself.
            put(JSONObject().apply {
                put("ip_cidr", JSONArray().put("223.5.5.5/32"))
                put("action", "direct")
            })
            if (includeTun) put(JSONObject().put("inbound", JSONArray(listOf("tun-in", "mixed-in"))).put("action", "sniff"))
            if (includeTun) {
                put(JSONObject().put("rule_set", JSONArray().put("geosite-cn")).put("outbound", "direct"))
                put(JSONObject().put("rule_set", JSONArray().put("geoip-cn")).put("outbound", "direct"))
                put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
            }
        })
        put("final", input.proxyTag)
    })
    put("dns", JSONObject().apply {
        put("servers", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "https")
                put("tag", "dns-bootstrap")
                put("server", "223.5.5.5")
                put("path", "/dns-query")
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", "dns.alidns.com")
                })
            })
            put(JSONObject().apply {
                put("type", "https")
                put("tag", "dns-remote")
                put("server", "dns.google")
                put("path", "/dns-query")
                put("detour", input.proxyTag)
                put("domain_resolver", DNS_SYSTEM_TAG)
            })
            put(JSONObject().apply {
                put("type", "https")
                put("tag", "dns-direct")
                put("server", "dns.alidns.com")
                put("path", "/dns-query")
                put("domain_resolver", "dns-bootstrap")
            })
            put(JSONObject().apply {
                put("type", "local")
                put("tag", DNS_SYSTEM_TAG)
            })
        })
        put("rules", JSONArray().apply {
            // `server` without an action is the pre-1.11 compatibility form. Keep the same
            // routing behavior with the explicit 1.14 DNS rule action before it is removed.
            if (includeTun) put(JSONObject().put("rule_set", JSONArray().put("geosite-cn"))
                .put("action", "route").put("server", "dns-direct")
                .put("timeout", DNS_QUERY_TIMEOUT))
            if (includeTun) put(JSONObject().put("inbound", JSONArray().put("tun-in"))
                .put("action", "route").put("server", "dns-remote")
                .put("timeout", DNS_QUERY_TIMEOUT))
        })
        // Node tests must use the selected node for DNS as well as HTTP; otherwise a
        // direct resolver can make a working node appear unavailable on restricted networks.
        put("final", "dns-remote")
        put("strategy", "prefer_ipv4")
        put("timeout", DNS_QUERY_TIMEOUT)
        put("optimistic", JSONObject().apply {
            put("enabled", true)
            put("timeout", DNS_OPTIMISTIC_TIMEOUT)
        })
    })
}.toString()

/**
 * One short-lived core for a complete manual latency batch. Every node gets its own localhost
 * mixed inbound and a terminal route to its own outbound, so requests can run concurrently
 * without selector reload races or restarting the process-global libbox command server.
 */
internal fun buildKotlinNodeTestConfig(routes: List<KotlinNodeTestRoute>): String = JSONObject().apply {
    require(routes.isNotEmpty()) { "At least one node test route is required" }
    require(routes.map(KotlinNodeTestRoute::inboundTag).distinct().size == routes.size) {
        "Node test inbound tags must be unique"
    }
    require(routes.map(KotlinNodeTestRoute::outboundTag).distinct().size == routes.size) {
        "Node test outbound tags must be unique"
    }
    require(routes.map(KotlinNodeTestRoute::mixedPort).distinct().size == routes.size) {
        "Node test ports must be unique"
    }
    require(routes.all { it.mixedPort in 1..65_535 }) { "Invalid node test port" }

    put("log", JSONObject().put("level", "warn"))
    put("outbounds", JSONArray().apply {
        routes.forEach { route -> put(buildSingBoxOutbound(route.bean, route.outboundTag)) }
        put(JSONObject().put("type", "direct").put("tag", "direct"))
    })
    put("inbounds", JSONArray().apply {
        routes.forEach { route ->
            put(JSONObject().apply {
                put("type", "mixed")
                put("tag", route.inboundTag)
                put("listen", "127.0.0.1")
                put("listen_port", route.mixedPort)
            })
        }
    })
    put("route", JSONObject().apply {
        // Platform control binds every native outbound socket to Android's physical network (or
        // asks the running VpnService to protect it). This keeps tests off NekoPilot's own TUN.
        put("auto_detect_interface", true)
        put("default_domain_resolver", DNS_SYSTEM_TAG)
        put("rules", JSONArray().apply {
            put(JSONObject().apply {
                put("ip_cidr", JSONArray().put("223.5.5.5/32"))
                put("action", "direct")
            })
            routes.forEach { route ->
                put(JSONObject().apply {
                    put("inbound", JSONArray().put(route.inboundTag))
                    put("outbound", route.outboundTag)
                })
            }
        })
        // All expected traffic is matched by an inbound rule. Direct is a safe fail-closed
        // fallback for internal bootstrap traffic rather than accidentally testing another node.
        put("final", "direct")
    })
    put("dns", JSONObject().apply {
        put("servers", JSONArray().apply {
            put(bootstrapDnsServer())
            put(JSONObject().apply {
                put("type", "local")
                put("tag", DNS_SYSTEM_TAG)
            })
        })
        put("final", "dns-bootstrap")
        put("strategy", "prefer_ipv4")
        put("timeout", DNS_QUERY_TIMEOUT)
        put("optimistic", JSONObject().apply {
            put("enabled", true)
            put("timeout", DNS_OPTIMISTIC_TIMEOUT)
        })
    })
}.toString()

private fun bootstrapDnsServer(): JSONObject = JSONObject().apply {
    put("type", "https")
    put("tag", "dns-bootstrap")
    put("server", "223.5.5.5")
    put("path", "/dns-query")
    put("tls", JSONObject().apply {
        put("enabled", true)
        put("server_name", "dns.alidns.com")
    })
}

private fun localRuleSet(tag: String, fileName: String, directory: String): JSONObject = JSONObject()
    .put("type", "local")
    .put("tag", tag)
    .put("format", "binary")
    .put("path", "$directory/$fileName")
