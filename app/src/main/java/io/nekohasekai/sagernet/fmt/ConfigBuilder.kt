package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import moe.matsuri.nb4a.proxy.config.ConfigBean

internal data class ParsedRulePorts(val ports: List<Int>, val ranges: List<String>)

internal fun parseRulePorts(text: String): ParsedRulePorts {
    val ports = linkedSetOf<Int>()
    val ranges = linkedSetOf<String>()
    text.split(',', '\n', '\r').forEach { rawToken ->
        val token = rawToken.trim()
        if (':' in token) {
            val bounds = token.split(':', limit = 2)
            val start = bounds[0].trim().toIntOrNull()
            val end = bounds.getOrNull(1)?.trim()?.toIntOrNull()
            if (start != null && end != null && start in 1..end && end <= 65_535) {
                ranges += "$start:$end"
            }
        } else {
            token.toIntOrNull()?.takeIf { it in 1..65_535 }?.let(ports::add)
        }
    }
    return ParsedRulePorts(ports.toList(), ranges.toList())
}

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var needsRootUidBypass: Boolean = false,
    var testOutbounds: Map<Long, String> = emptyMap(),
    var selectorOutbounds: Map<Long, String> = emptyMap(),
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

/** Builds standard sing-box 1.14 JSON from the selected persisted node. */
fun buildConfig(
    proxy: ProxyEntity,
    forTest: Boolean = false,
    forExport: Boolean = false,
    testProfiles: List<ProxyEntity>? = null,
    selectorProfiles: List<ProxyEntity>? = null,
): ConfigBuildResult {
    val selectedBean = proxy.requireBean()
    if (proxy.type == TYPE_CONFIG && (selectedBean as ConfigBean).type == 0) {
        return ConfigBuildResult(
            selectedBean.config,
            emptyList(),
        )
    }
    val runtimeSelectorProfiles = selectorProfiles
        ?.distinctBy(ProxyEntity::id)
        ?.takeIf { profiles -> profiles.any { it.id == proxy.id } }
        ?: listOf(proxy)
    runtimeSelectorProfiles.forEach { profile ->
        unsupportedOfficialRuntimeProfileName(profile.requireBean())?.let { profileName ->
            throw UnsupportedOperationException(
                SagerNet.application.getString(
                    R.string.profile_not_supported_by_official_runtime,
                    profileName,
                ),
            )
        }
    }
    return ConfigBuildResult(
        config = buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = selectedBean,
                selectedProfileId = proxy.id,
                selectorNodes = runtimeSelectorProfiles.map { profile ->
                    KotlinSelectorNode(profile.id, profile.requireBean())
                },
                useVpn = !forExport,
                routeRules = loadKotlinRouteRules(),
                mixedPort = DataStore.mixedPort,
                mixedUsername = DataStore.mixedProxyUsername,
                mixedPassword = DataStore.mixedProxyPassword,
                allowAccess = DataStore.allowAccess,
                ruleAssetDirectory = SagerNet.application.externalAssets.absolutePath,
                forTest = forTest,
            ),
        ),
        externalIndex = emptyList(),
    )
}

/** Resolves persisted package names at the Android boundary before configuration compilation. */
internal fun loadKotlinRouteRules(): List<KotlinRouteRule> =
    SagerDatabase.rulesDao.enabledRules().map { rule ->
        rule.toKotlinRouteRule { packageName ->
            runCatching {
                SagerNet.application.packageManager.getApplicationInfo(packageName, 0).uid
            }.getOrNull()
        }
    }

internal fun RuleEntity.toKotlinRouteRule(resolveUid: (String) -> Int?): KotlinRouteRule {
    val configuredPackages = packages.map(String::trim).filter(String::isNotEmpty)
    return KotlinRouteRule(
        id = id,
        name = name,
        customConfig = config,
        domains = domains,
        ip = ip,
        port = port,
        sourcePort = sourcePort,
        network = network,
        source = source,
        protocol = protocol,
        outbound = outbound,
        userIds = configuredPackages.mapNotNull(resolveUid).distinct().sorted(),
        packagesConfigured = configuredPackages.isNotEmpty(),
    )
}
