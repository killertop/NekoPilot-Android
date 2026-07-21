package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.core.GoDataCore
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import libcore.Libcore
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.json.JSONArray
import org.json.JSONObject

/** Converts Go's portable profile DTOs into the existing persisted bean ABI. */
internal fun parseProfilesWithGo(text: String): List<AbstractBean> {
    return parseGoProfiles(Libcore.parseProfileLinks(text))
}

internal fun parseProfileDocumentWithGo(text: String): List<AbstractBean> {
    return parseGoProfiles(Libcore.parseProfileDocument(text))
}

internal data class ParsedSubscriptionDocument(
    val profiles: List<AbstractBean>,
    val skippedNames: Set<String>,
    val hasUnnamedSkipped: Boolean,
)

internal fun parseSubscriptionDocumentWithGo(text: String): ParsedSubscriptionDocument {
    val result = JSONObject(Libcore.parseSubscriptionDocument(text))
    val skipped = subscriptionSkippedNames(result)
    return ParsedSubscriptionDocument(
        profiles = parseGoProfiles(result.getJSONArray("profiles").toString()),
        skippedNames = buildSet(skipped.length()) {
            repeat(skipped.length()) { index ->
                skipped.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        },
        hasUnnamedSkipped = result.optBoolean("hasUnnamedSkipped"),
    )
}

/** Accept legacy JSON null, but fail closed on a malformed native contract. */
internal fun subscriptionSkippedNames(result: JSONObject): JSONArray {
    require(result.has("skippedNames")) { "Go subscription metadata is missing skippedNames" }
    return when (val skipped = result.opt("skippedNames")) {
        JSONObject.NULL -> JSONArray()
        is JSONArray -> skipped
        else -> error("Go subscription metadata skippedNames must be an array")
    }
}

internal fun parseGoProfiles(encoded: String): List<AbstractBean> {
    val profiles = JSONArray(encoded)
    require(profiles.length() <= GoDataCore.MAX_SUBSCRIPTION_PROFILES) {
        "Profile document contains too many profiles"
    }
    return List(profiles.length()) { index ->
        val profile = profiles.getJSONObject(index)
        val bean = when (val kind = profile.getString("kind")) {
            "socks" -> gson.fromJson(profile.toString(), SOCKSBean::class.java)
            "http" -> gson.fromJson(profile.toString(), HttpBean::class.java)
            "ss" -> gson.fromJson(profile.toString(), ShadowsocksBean::class.java)
            "vmess", "vless" -> gson.fromJson(profile.toString(), VMessBean::class.java)
            "trojan" -> gson.fromJson(profile.toString(), TrojanBean::class.java)
            "trojan-go" -> gson.fromJson(profile.toString(), TrojanGoBean::class.java)
            "mieru" -> gson.fromJson(profile.toString(), MieruBean::class.java)
            "naive" -> gson.fromJson(profile.toString(), NaiveBean::class.java)
            "hysteria", "hysteria2" -> gson.fromJson(profile.toString(), HysteriaBean::class.java)
            "tuic" -> gson.fromJson(profile.toString(), TuicBean::class.java)
            "ssh" -> gson.fromJson(profile.toString(), SSHBean::class.java)
            "wireguard" -> gson.fromJson(profile.toString(), WireGuardBean::class.java)
            "shadowtls" -> gson.fromJson(profile.toString(), ShadowTLSBean::class.java)
            "anytls" -> gson.fromJson(profile.toString(), AnyTLSBean::class.java)
            "chain" -> gson.fromJson(profile.toString(), ChainBean::class.java)
            "neko" -> gson.fromJson(profile.toString(), NekoBean::class.java)
            "config" -> gson.fromJson(profile.toString(), ConfigBean::class.java)
            else -> error("Unsupported Go profile kind: $kind")
        }
        bean.initializeDefaultValues()
        bean
    }
}

internal data class NormalizedProfiles(
    val profiles: List<AbstractBean>,
    val duplicates: List<String>,
)

internal fun normalizeProfilesWithGo(
    profiles: List<AbstractBean>,
    deduplicate: Boolean,
): NormalizedProfiles {
    require(profiles.size <= GoDataCore.MAX_SUBSCRIPTION_PROFILES) {
        "Profile document contains too many profiles"
    }
    val portable = JSONArray()
    profiles.forEach { bean ->
        portable.put(org.json.JSONObject(gson.toJson(bean)).put("kind", profileKindForGo(bean)))
    }
    val result = org.json.JSONObject(Libcore.normalizeProfileSet(portable.toString(), deduplicate))
    val duplicateArray = result.getJSONArray("duplicates")
    return NormalizedProfiles(
        parseGoProfiles(result.getJSONArray("profiles").toString()),
        List(duplicateArray.length()) { duplicateArray.getString(it) },
    )
}

internal fun profileKindForGo(bean: AbstractBean): String = when (bean) {
    is ShadowTLSBean -> "shadowtls"
    is HttpBean -> "http"
    is VMessBean -> if (bean.isVLESS) "vless" else "vmess"
    is TrojanBean -> "trojan"
    is TrojanGoBean -> "trojan-go"
    is MieruBean -> "mieru"
    is NaiveBean -> "naive"
    is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria" else "hysteria2"
    is TuicBean -> "tuic"
    is SOCKSBean -> "socks"
    is ShadowsocksBean -> "ss"
    is SSHBean -> "ssh"
    is WireGuardBean -> "wireguard"
    is AnyTLSBean -> "anytls"
    is ChainBean -> "chain"
    is NekoBean -> "neko"
    is ConfigBean -> "config"
    else -> error("Unsupported Go profile bean: ${bean.javaClass.simpleName}")
}

internal fun encodeProfileLinkWithGo(bean: AbstractBean): String {
    val kind = when (bean) {
        is SOCKSBean -> "socks"
        is HttpBean -> "http"
        is ShadowsocksBean -> "ss"
        is VMessBean -> if (bean.isVLESS) "vless" else "vmess"
        is TrojanBean -> "trojan"
        is TrojanGoBean -> "trojan-go"
        is NaiveBean -> "naive"
        is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria" else "hysteria2"
        is TuicBean -> "tuic"
        is AnyTLSBean -> "anytls"
        else -> error("Unsupported Go profile link bean: ${bean.javaClass.simpleName}")
    }
    return Libcore.encodeProfileLink(kind, gson.toJson(bean))
}
