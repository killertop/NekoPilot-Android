package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.json.JSONArray
import org.json.JSONObject

const val TAG_MIXED = "mixed-in"
const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val LOCALHOST = "127.0.0.1"

internal fun mixedInboundBind(forTest: Boolean, allowAccess: Boolean) =
    if (!forTest && allowAccess) "0.0.0.0" else LOCALHOST

internal data class ParsedRulePorts(val ports: List<Int>, val ranges: List<String>)

internal fun parseRulePorts(text: String): ParsedRulePorts {
    val result = JSONObject(Libcore.normalizeRulePorts(text))
    val ports = result.getJSONArray("ports")
    val ranges = result.getJSONArray("ranges")
    return ParsedRulePorts(
        List(ports.length()) { ports.getInt(it) },
        List(ranges.length()) { ranges.getString(it) },
    )
}

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var needsRootUidBypass: Boolean = false,
    var testOutbounds: Map<Long, String> = emptyMap(),
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

/**
 * Builds the Android-neutral input snapshot consumed by Go's ConfigCompiler.
 * Android owns persistence and package/UID lookup; Go owns every sing-box
 * option, route, DNS, chain and custom-config merge from this boundary onward.
 */
fun buildConfig(
    proxy: ProxyEntity,
    forTest: Boolean = false,
    forExport: Boolean = false,
    testProfiles: List<ProxyEntity>? = null,
): ConfigBuildResult {
    val selectedBean = proxy.requireBean()
    if (proxy.type == TYPE_CONFIG && (selectedBean as ConfigBean).type == 0) {
        return ConfigBuildResult(
            selectedBean.config,
            emptyList(),
        )
    }

    val profiles = SagerDatabase.proxyDao.getAll().associateBy(ProxyEntity::id).toMutableMap().apply {
        putIfAbsent(proxy.id, proxy)
    }
    val groups = SagerDatabase.groupDao.allGroups()
    val rules = if (forTest) emptyList() else ProfileManager.getRules().filter { it.enabled }
    val isVpn = DataStore.serviceMode == Key.MODE_VPN

    if (rules.any { it.packages.isNotEmpty() }) PackageCache.awaitLoadSync()

    val request = JSONObject().apply {
        put("selectedId", proxy.id)
        testProfiles?.map(ProxyEntity::id)?.takeIf { it.isNotEmpty() }?.let {
            put("testIds", JSONArray(it))
        }
        put("forTest", forTest)
        put("forExport", forExport)
        put("settings", JSONObject().apply {
            put("isVpn", isVpn)
            put("allowAccess", DataStore.allowAccess)
            put("mixedPort", DataStore.mixedPort)
            put("mixedUsername", DataStore.mixedProxyUsername)
            put("mixedPassword", DataStore.mixedProxyPassword)
            put("tunImplementation", DataStore.tunImplementation)
            put("mtu", DataStore.mtu)
            put("ipv6Mode", DataStore.ipv6Mode)
            put("trafficSniffing", DataStore.trafficSniffing)
            put("resolveDestination", DataStore.resolveDestination)
            put("remoteDns", DataStore.remoteDns)
            put("directDns", DataStore.directDns)
            put("enableDnsRouting", DataStore.enableDnsRouting)
            put("enableFakeDns", DataStore.enableFakeDns)
            put("bypassLanInCore", DataStore.bypassLanInCore)
            put("logLevel", 0)
            put("globalAllowInsecure", DataStore.globalAllowInsecure)
            put("globalCustomConfig", DataStore.globalCustomConfig)
            put("serverDomainStrategy", domainStrategy("domain_strategy_for_server", "prefer_ipv4"))
            put("remoteDnsStrategy", domainStrategy("domain_strategy_for_remote", ""))
            put("directDnsStrategy", domainStrategy("domain_strategy_for_direct", ""))
        })
        put("profiles", JSONArray().apply {
            profiles.values.sortedBy(ProxyEntity::id).forEach { entity ->
                put(profileSnapshot(entity))
            }
        })
        put("groups", JSONArray().apply {
            groups.forEach { group ->
                put(JSONObject().apply {
                    put("id", group.id)
                    put("frontProxy", group.frontProxy)
                    put("landingProxy", group.landingProxy)
                })
            }
        })
        put("rules", JSONArray().apply {
            rules.forEach { rule ->
                val uids = rule.packages.mapNotNull { packageName ->
                    if (!isVpn) {
                        Toast.makeText(
                            SagerNet.application,
                            SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    PackageCache[packageName]?.takeIf { it >= 1000 }
                }.distinct()
                put(JSONObject().apply {
                    put("id", rule.id)
                    put("name", rule.displayName())
                    put("config", rule.config)
                    put("domains", rule.domains)
                    put("ip", rule.ip)
                    put("port", rule.port)
                    put("sourcePort", rule.sourcePort)
                    put("network", rule.network)
                    put("source", rule.source)
                    put("protocol", rule.protocol)
                    put("outbound", rule.outbound)
                    put("uids", JSONArray(uids))
                })
            }
        })
    }

    val response = JSONObject(Libcore.compileClientConfig(request.toString()))
    val testOutbounds = response.optJSONObject("testOutbounds")?.let { values ->
        buildMap {
            values.keys().forEach { key ->
                put(key.toLong(), values.getString(key))
            }
        }
    } ?: emptyMap()
    val externalIndex = response.getJSONArray("externalChains").let { chains ->
        List(chains.length()) { chainIndex ->
            val chain = LinkedHashMap<Int, ProxyEntity>()
            val entries = chains.getJSONArray(chainIndex)
            repeat(entries.length()) { entryIndex ->
                val entry = entries.getJSONObject(entryIndex)
                val entity = profiles[entry.getLong("profileId")]
                    ?: error("Go returned an unknown profile id")
                entity.requireBean().apply {
                    finalAddress = entry.getString("finalAddress")
                    finalPort = entry.getInt("finalPort")
                }
                chain[entry.getInt("port")] = entity
            }
            IndexEntity(chain)
        }
    }
    response.optJSONArray("warnings")?.let { warnings ->
        repeat(warnings.length()) { index ->
            Toast.makeText(SagerNet.application, warnings.getString(index), Toast.LENGTH_LONG).show()
        }
    }
    return ConfigBuildResult(
        config = response.getString("config"),
        externalIndex = externalIndex,
        needsRootUidBypass = profiles.values.any {
            it.hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
        },
        testOutbounds = testOutbounds,
    )
}

private fun profileSnapshot(entity: ProxyEntity): JSONObject {
    val bean = entity.requireBean()
    val kind = profileKindForGo(bean)
    val external = entity.needExternal()
    var skipMappingWhenLast = false
    if (external && bean is HysteriaBean) {
        val pluginId = if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
        skipMappingWhenLast = Plugins.isUsingMatsuriExe(pluginId)
    }
    return JSONObject().apply {
        put("id", entity.id)
        put("groupId", entity.groupId)
        put("kind", kind)
        put("bean", JSONObject(gson.toJson(bean)))
        put("external", external)
        put("canMapping", bean.canMapping())
        put("skipMappingWhenLast", skipMappingWhenLast)
        (bean as? ChainBean)?.let { put("chain", JSONArray(it.proxies)) }
        entity.singMux()?.takeIf { it.enabled == true }?.let { mux ->
            put("multiplex", JSONObject().apply {
                put("enabled", true)
                put("padding", mux.padding)
                put("max_streams", mux.maxStreams)
                put("protocol", mux.protocol)
            })
        }
    }
}

private fun domainStrategy(key: String, automatic: String): String {
    return (DataStore.configurationStore.getString(key) ?: "auto").replace("auto", automatic)
}
