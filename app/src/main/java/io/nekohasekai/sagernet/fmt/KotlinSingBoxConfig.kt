package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.DEFAULT_TUN_MTU
import org.json.JSONArray
import org.json.JSONObject

private const val DNS_SYSTEM_TAG = "dns-system"
private const val DNS_QUERY_TIMEOUT = "5s"
private const val DNS_OPTIMISTIC_TIMEOUT = "5m"
private val DNS_LOCAL_DOMAIN_SUFFIXES = listOf(
    "local",
    "lan",
    "localdomain",
    "localhost",
    "home.arpa",
)

internal data class KotlinSingBoxConfigInput(
    val selected: AbstractBean,
    val selectedProfileId: Long = 0L,
    val selectorNodes: List<KotlinSelectorNode> = emptyList(),
    val routeRules: List<KotlinRouteRule> = emptyList(),
    val proxyTag: String = "proxy",
    val useVpn: Boolean,
    val tunStack: String = "mixed",
    val mixedPort: Int = 20_880,
    /**
     * A loopback-only, authenticated mixed inbound used exclusively by runtime health probes.
     * Its route and DNS rules are pinned to the selected proxy before user direct rules run.
     */
    val healthCheckPort: Int? = null,
    val mixedUsername: String = "",
    val mixedPassword: String = "",
    val allowAccess: Boolean = false,
    val ruleAssetDirectory: String,
    val forTest: Boolean = false,
)

internal data class KotlinSelectorNode(val profileId: Long, val bean: AbstractBean) {
    val tag: String get() = "node-$profileId"
}

/** Runtime-ready form of a persisted user route rule. Package names are resolved to UIDs by the
 * Android process before this pure configuration compiler is called. */
internal data class KotlinRouteRule(
    val id: Long,
    val name: String = "",
    val customConfig: String = "",
    val domains: String = "",
    val ip: String = "",
    val port: String = "",
    val sourcePort: String = "",
    val network: String = "",
    val source: String = "",
    val protocol: String = "",
    val outbound: Long = 0L,
    val userIds: List<Int> = emptyList(),
    val packagesConfigured: Boolean = false,
)

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
    input.healthCheckPort?.let { healthCheckPort ->
        require(healthCheckPort in 1..65_535) { "Invalid health check port" }
        require(healthCheckPort != input.mixedPort) {
            "Health check port must differ from local proxy port"
        }
        require(input.mixedUsername.isNotBlank() && input.mixedPassword.isNotBlank()) {
            "Health check inbound requires local proxy credentials"
        }
    }
    val exposeMixedInbound = input.allowAccess && !input.forTest
    require(!exposeMixedInbound || (
        input.mixedUsername.isNotBlank() && input.mixedPassword.isNotBlank()
    )) {
        "LAN access requires both a mixed-inbound username and password"
    }
    val includeTun = input.useVpn && !input.forTest
    val selectorNodes = input.selectorNodes.distinctBy(KotlinSelectorNode::profileId)
    val useSelector = selectorNodes.size > 1 &&
        input.selectedProfileId > 0L &&
        selectorNodes.any { it.profileId == input.selectedProfileId }
    val compiledUserRules = input.routeRules.mapNotNull { rule ->
        compileKotlinRouteRule(rule, input, selectorNodes)
    }
    // Enabled persisted rules are the single source of truth, including the two default China
    // direct rules. Do not inject a second unconditional copy here: otherwise disabling a
    // default rule in the UI cannot affect the live VPN configuration.
    val configuredRuleSetTags = compiledUserRules.flatMap { it.ruleSetTags }.distinct()
    put("log", JSONObject().put("level", "warn"))
    val endpoints = JSONArray()
    put("outbounds", JSONArray().apply {
        if (useSelector) {
            selectorNodes.forEach { node ->
                appendSingBoxNode(node.bean, node.tag, this, endpoints)
            }
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
            appendSingBoxNode(input.selected, input.proxyTag, this, endpoints)
        }
        put(JSONObject().put("type", "direct").put("tag", "direct"))
    })
    if (endpoints.length() > 0) put("endpoints", endpoints)
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
            put("listen", if (exposeMixedInbound) "0.0.0.0" else "127.0.0.1")
            put("listen_port", input.mixedPort)
            if (exposeMixedInbound) {
                put("users", JSONArray().put(JSONObject().apply {
                    put("username", input.mixedUsername)
                    put("password", input.mixedPassword)
                }))
            }
        })
        input.healthCheckPort?.let { healthCheckPort ->
            put(JSONObject().apply {
                // A normal user route may intentionally send an address direct. Runtime health
                // probes need different semantics: they prove the selected proxy itself works.
                put("type", "mixed")
                put("tag", "health-in")
                put("listen", "127.0.0.1")
                put("listen_port", healthCheckPort)
                put("users", JSONArray().put(JSONObject().apply {
                    put("username", input.mixedUsername)
                    put("password", input.mixedPassword)
                }))
            })
        }
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
        if (configuredRuleSetTags.isNotEmpty()) put("rule_set", JSONArray().apply {
            configuredRuleSetTags.forEach { tag ->
                put(localRuleSet(tag, requiredRuleSetFile(tag), input.ruleAssetDirectory))
            }
        })
        put("rules", JSONArray().apply {
            input.healthCheckPort?.let {
                // This must precede every bootstrap/user/default direct rule. The dedicated
                // inbound is private to VpnService and cannot validate an egress request by
                // accidentally taking a user-selected direct route.
                put(JSONObject().apply {
                    put("inbound", JSONArray().put("health-in"))
                    put("outbound", input.proxyTag)
                })
            }
            // The bootstrap resolver must stay direct; otherwise it would need the proxy
            // before it can resolve the proxy endpoint itself.
            put(JSONObject().apply {
                put("ip_cidr", JSONArray().put("223.5.5.5/32"))
                put("action", "direct")
            })
            if (includeTun) put(JSONObject().put("inbound", JSONArray(listOf("tun-in", "mixed-in"))).put("action", "sniff"))
            compiledUserRules.forEach { compiled -> put(compiled.routeRule) }
            if (includeTun) {
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
            input.healthCheckPort?.let {
                // Keep the health probe's DNS on the same selected-proxy path as its HTTP
                // request. This also prevents a user direct DNS rule from masking a dead node.
                put(JSONObject().apply {
                    put("inbound", JSONArray().put("health-in"))
                    put("action", "route")
                    put("server", "dns-remote")
                    put("timeout", DNS_QUERY_TIMEOUT)
                })
            }
            // Keep platform-local names on the physical network. This must precede both the
            // China rule-set and the remote fallback so LAN discovery never depends on a proxy.
            put(JSONObject().apply {
                // `preferred_by` resolves DNS transport tags in the pinned libbox runtime. The
                // transport's type is `local`, but its tag is `dns-system`; using the type here
                // makes libbox fail during service startup with "DNS server not found: local".
                put("preferred_by", JSONArray().put(DNS_SYSTEM_TAG))
                put("action", "route")
                put("server", DNS_SYSTEM_TAG)
            })
            put(JSONObject().apply {
                put("domain_suffix", JSONArray(DNS_LOCAL_DOMAIN_SUFFIXES))
                put("action", "route")
                put("server", DNS_SYSTEM_TAG)
            })
            compiledUserRules.mapNotNull(CompiledKotlinRouteRule::dnsRule)
                .forEach { dnsRule -> put(dnsRule) }
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

private data class CompiledKotlinRouteRule(
    val routeRule: JSONObject,
    val dnsRule: JSONObject?,
    val ruleSetTags: List<String>,
)

private val BUILT_IN_RULE_SET_FILES = mapOf(
    "geosite-cn" to "geosite-cn.srs",
    "geoip-cn" to "geoip-cn.srs",
)

private fun compileKotlinRouteRule(
    rule: KotlinRouteRule,
    input: KotlinSingBoxConfigInput,
    selectorNodes: List<KotlinSelectorNode>,
): CompiledKotlinRouteRule? {
    // An uninstalled package cannot ever be the connection owner. Keeping a stale rule would
    // create a catch-all rule if it had no other matcher, so leave it inactive until the package
    // returns instead of silently applying it to unrelated traffic.
    if (rule.packagesConfigured && rule.userIds.isEmpty()) return null

    val routeRule = JSONObject()
    appendRouteUserIds(routeRule, rule.userIds)
    applyRouteDomainMatchers(routeRule, rule.domains)
    applyRouteIpMatchers(routeRule, rule.ip)
    applyRoutePortMatchers(routeRule, rule.port, source = false)
    applyRoutePortMatchers(routeRule, rule.sourcePort, source = true)
    putRouteStringArray(routeRule, "network", splitRouteValues(rule.network))
    putRouteStringArray(routeRule, "source_ip_cidr", splitRouteValues(rule.source))
    putRouteStringArray(routeRule, "protocol", splitRouteValues(rule.protocol))
    mergeCustomRouteFields(routeRule, rule.customConfig)

    require(hasRouteMatcher(routeRule)) {
        "Route rule ${rule.id} has no match condition"
    }
    when (rule.outbound) {
        -2L -> {
            routeRule.remove("outbound")
            routeRule.put("action", "reject")
        }

        else -> {
            require(!routeRule.has("action")) {
                "Route rule ${rule.id} custom config cannot override its routing action"
            }
            routeRule.put("outbound", resolveRouteOutboundTag(rule, input, selectorNodes))
        }
    }
    val ruleSetTags = routeRule.optJSONArray("rule_set")?.toStringList().orEmpty()
    ruleSetTags.forEach(::requiredRuleSetFile)

    return CompiledKotlinRouteRule(
        routeRule = routeRule,
        dnsRule = buildKotlinDnsRule(rule),
        ruleSetTags = ruleSetTags,
    )
}

private fun resolveRouteOutboundTag(
    rule: KotlinRouteRule,
    input: KotlinSingBoxConfigInput,
    selectorNodes: List<KotlinSelectorNode>,
): String = when (rule.outbound) {
    0L -> input.proxyTag
    -1L -> "direct"
    else -> when {
        rule.outbound == input.selectedProfileId -> input.proxyTag
        else -> selectorNodes.firstOrNull { it.profileId == rule.outbound }?.tag
            ?: error("Route rule ${rule.id} references an outbound that is not running")
    }
}

private fun buildKotlinDnsRule(rule: KotlinRouteRule): JSONObject? {
    if (rule.packagesConfigured && rule.userIds.isEmpty()) return null
    val dnsRule = JSONObject()
    appendRouteUserIds(dnsRule, rule.userIds)
    applyRouteDomainMatchers(dnsRule, rule.domains)
    if (!hasDnsMatcher(dnsRule)) return null
    when (rule.outbound) {
        -2L -> {
            dnsRule.put("action", "predefined")
            dnsRule.put("rcode", "NOERROR")
        }

        -1L -> {
            dnsRule.put("action", "route")
            dnsRule.put("server", "dns-direct")
        }

        else -> {
            dnsRule.put("action", "route")
            dnsRule.put("server", "dns-remote")
        }
    }
    return dnsRule
}

private fun appendRouteUserIds(target: JSONObject, userIds: List<Int>) {
    userIds.distinct().sorted().takeIf { it.isNotEmpty() }?.let { ids ->
        target.put("user_id", JSONArray(ids))
    }
}

private fun applyRouteDomainMatchers(target: JSONObject, raw: String) {
    splitRouteValues(raw).forEach { value ->
        when {
            value.startsWith("rule_set:") -> appendRouteString(
                target,
                "rule_set",
                value.removePrefix("rule_set:").trim(),
            )

            value.startsWith("full:") -> appendRouteString(
                target,
                "domain",
                value.removePrefix("full:").trim().lowercase(),
            )

            value.startsWith("domain:") -> appendRouteString(
                target,
                "domain_suffix",
                value.removePrefix("domain:").trim().lowercase(),
            )

            value.startsWith("regexp:") -> appendRouteString(
                target,
                "domain_regex",
                value.removePrefix("regexp:").trim(),
            )

            value.startsWith("keyword:") -> appendRouteString(
                target,
                "domain_keyword",
                value.removePrefix("keyword:").trim().lowercase(),
            )

            else -> appendRouteString(target, "domain_suffix", value.lowercase())
        }
    }
}

private fun applyRouteIpMatchers(target: JSONObject, raw: String) {
    splitRouteValues(raw).forEach { value ->
        when {
            value == "private" -> target.put("ip_is_private", true)
            value.startsWith("rule_set:") -> appendRouteString(
                target,
                "rule_set",
                value.removePrefix("rule_set:").trim(),
            )

            else -> appendRouteString(target, "ip_cidr", value)
        }
    }
}

private fun applyRoutePortMatchers(target: JSONObject, raw: String, source: Boolean) {
    val parsed = parseRulePorts(raw)
    val portKey = if (source) "source_port" else "port"
    val rangeKey = if (source) "source_port_range" else "port_range"
    parsed.ports.takeIf { it.isNotEmpty() }?.let { ports -> target.put(portKey, JSONArray(ports)) }
    parsed.ranges.takeIf { it.isNotEmpty() }?.let { ranges -> target.put(rangeKey, JSONArray(ranges)) }
}

private fun putRouteStringArray(target: JSONObject, key: String, values: List<String>) {
    values.takeIf { it.isNotEmpty() }?.let { target.put(key, JSONArray(it)) }
}

private fun appendRouteString(target: JSONObject, key: String, value: String) {
    if (value.isBlank()) return
    val values = target.optJSONArray(key) ?: JSONArray().also { target.put(key, it) }
    if ((0 until values.length()).none { index -> values.optString(index) == value }) {
        values.put(value)
    }
}

private fun mergeCustomRouteFields(target: JSONObject, raw: String) {
    if (raw.isBlank()) return
    val custom = JSONObject(raw)
    custom.keys().asSequence().toList().forEach { key ->
        val value = custom.get(key)
        if (key.endsWith("+")) {
            val targetKey = key.removeSuffix("+")
            val additions = value as? JSONArray
                ?: error("Route rule custom field $key must be a JSON array")
            val destination = target.optJSONArray(targetKey)
                ?: JSONArray().also { target.put(targetKey, it) }
            (0 until additions.length()).forEach { index -> destination.put(additions.get(index)) }
        } else {
            target.put(key, value)
        }
    }
}

private fun hasRouteMatcher(rule: JSONObject): Boolean = rule.keys().asSequence().any { key ->
    key !in setOf("outbound", "action")
}

private fun hasDnsMatcher(rule: JSONObject): Boolean = rule.keys().asSequence().any { key ->
    key !in setOf("action", "server", "rcode", "timeout")
}

private fun splitRouteValues(value: String): List<String> = value
    .split(',', '\n', '\r')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()

private fun JSONArray.toStringList(): List<String> = buildList {
    (0 until this@toStringList.length()).forEach { index ->
        this@toStringList.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
    }
}.distinct()

private fun requiredRuleSetFile(tag: String): String = BUILT_IN_RULE_SET_FILES[tag]
    ?: error("Unsupported route rule-set: $tag")

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
    val endpoints = JSONArray()
    put("outbounds", JSONArray().apply {
        routes.forEach { route -> appendSingBoxNode(route.bean, route.outboundTag, this, endpoints) }
        put(JSONObject().put("type", "direct").put("tag", "direct"))
    })
    if (endpoints.length() > 0) put("endpoints", endpoints)
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

private fun appendSingBoxNode(
    bean: AbstractBean,
    tag: String,
    outbounds: JSONArray,
    endpoints: JSONArray,
) {
    if (bean is io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean) {
        endpoints.put(buildSingBoxEndpoint(bean, tag))
    } else {
        outbounds.put(buildSingBoxOutbound(bean, tag))
    }
}

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
