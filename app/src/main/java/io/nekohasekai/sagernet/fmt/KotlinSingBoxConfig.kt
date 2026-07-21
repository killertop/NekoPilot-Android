package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.DEFAULT_TUN_MTU
import org.json.JSONArray
import org.json.JSONObject

internal data class KotlinSingBoxConfigInput(
    val selected: AbstractBean,
    val useVpn: Boolean,
    val tunStack: String = "mixed",
    val mixedPort: Int = 20_880,
    val mixedUsername: String = "",
    val mixedPassword: String = "",
    val allowAccess: Boolean = false,
    val ruleAssetDirectory: String,
    val forTest: Boolean = false,
)

/**
 * Minimal product configuration for one selected node. No chain, plugin, custom JSON, or Clash
 * compatibility is retained: all runtime schema is standard sing-box 1.14 JSON.
 */
internal fun buildKotlinSingBoxConfig(input: KotlinSingBoxConfigInput): String = JSONObject().apply {
    put("log", JSONObject().put("level", "warn"))
    put("outbounds", JSONArray().apply {
        put(buildSingBoxOutbound(input.selected, "proxy"))
        put(JSONObject().put("type", "direct").put("tag", "direct"))
    })
    if (!input.forTest) {
        put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("stack", input.tunStack)
                put("mtu", DEFAULT_TUN_MTU)
                put("address", JSONArray(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")))
                put("auto_route", true)
                put("dns_mode", "hijack")
            })
            put(JSONObject().apply {
                put("type", "mixed")
                put("tag", "mixed-in")
                put("listen", if (input.allowAccess) "0.0.0.0" else "127.0.0.1")
                put("listen_port", input.mixedPort)
                if (input.mixedUsername.isNotBlank() || input.mixedPassword.isNotBlank()) {
                    put("users", JSONArray().put(JSONObject().apply {
                        put("username", input.mixedUsername)
                        put("password", input.mixedPassword)
                    }))
                }
            })
        })
    }
    put("route", JSONObject().apply {
        put("auto_detect_interface", true)
        put("rule_set", JSONArray().apply {
            put(localRuleSet("geosite-cn", "geosite-cn.srs", input.ruleAssetDirectory))
            put(localRuleSet("geoip-cn", "geoip-cn.srs", input.ruleAssetDirectory))
        })
        put("rules", JSONArray().apply {
            if (!input.forTest) put(JSONObject().put("inbound", JSONArray(listOf("tun-in", "mixed-in"))).put("action", "sniff"))
            put(JSONObject().put("rule_set", JSONArray().put("geosite-cn")).put("outbound", "direct"))
            put(JSONObject().put("rule_set", JSONArray().put("geoip-cn")).put("outbound", "direct"))
            put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        })
        put("final", "proxy")
    })
    put("dns", JSONObject().apply {
        put("servers", JSONArray().apply {
            put(JSONObject().put("type", "local").put("tag", "dns-local"))
            put(JSONObject().apply {
                put("type", "https")
                put("tag", "dns-remote")
                put("server", "dns.google")
                put("path", "/dns-query")
                put("detour", "proxy")
                put("domain_resolver", "dns-local")
            })
            put(JSONObject().apply {
                put("type", "https")
                put("tag", "dns-direct")
                put("server", "dns.alidns.com")
                put("path", "/dns-query")
                put("domain_resolver", "dns-local")
            })
        })
        put("rules", JSONArray().apply {
            put(JSONObject().put("rule_set", JSONArray().put("geosite-cn")).put("server", "dns-direct"))
            if (!input.forTest) put(JSONObject().put("inbound", JSONArray().put("tun-in")).put("server", "dns-remote"))
        })
        put("final", if (input.forTest) "dns-direct" else "dns-remote")
        put("strategy", "prefer_ipv4")
    })
}.toString()

private fun localRuleSet(tag: String, fileName: String, directory: String): JSONObject = JSONObject()
    .put("type", "local")
    .put("tag", tag)
    .put("format", "binary")
    .put("path", "$directory/$fileName")
