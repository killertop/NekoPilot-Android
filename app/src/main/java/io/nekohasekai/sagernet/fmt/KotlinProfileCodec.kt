package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.core.SubscriptionDataCore
import io.nekohasekai.sagernet.ktx.MAX_PROFILE_ENTRIES
import io.nekohasekai.sagernet.ktx.MAX_PROFILE_IMPORT_BYTES
import io.nekohasekai.sagernet.ktx.MAX_PROFILE_LINK_CHARS
import io.nekohasekai.sagernet.ktx.requireUtf8BytesAtMost
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.parseVless
import io.nekohasekai.sagernet.fmt.v2ray.parseVmess
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Raw sing-box configurations can control listeners, routing, DNS, and logging. They are only
 * trusted when a user creates or edits them locally in the custom-configuration editor.
 */
internal class ExternalRawConfigImportException :
    IllegalArgumentException("External raw configuration imports are not allowed")

/** Parses the supported node links directly into Kotlin-owned persisted models. */
internal fun parseProfiles(text: String): List<AbstractBean> {
    val local = arrayListOf<AbstractBean>()
    forEachBoundedProfileLink(text, MAX_PROFILE_ENTRIES) { link ->
        local += parseExternalProfileLink(link)
    }
    return local
}

internal fun parseProfileDocument(text: String): List<AbstractBean> {
    return parseDocument(
        text = text,
        maxCandidateLinks = MAX_PROFILE_ENTRIES,
        rejectExternalRawConfig = true,
    ).profiles
}

internal data class ParsedSubscriptionDocument(
    val profiles: List<AbstractBean>,
    val skippedNames: Set<String>,
    val hasUnnamedSkipped: Boolean,
)

internal fun parseSubscriptionDocument(
    text: String,
    maxCandidateLinks: Int = SubscriptionDataCore.MAX_SUBSCRIPTION_PROFILES,
): ParsedSubscriptionDocument = parseDocument(
    text = text,
    maxCandidateLinks = maxCandidateLinks,
    rejectExternalRawConfig = false,
)

private fun parseDocument(
    text: String,
    maxCandidateLinks: Int,
    rejectExternalRawConfig: Boolean,
): ParsedSubscriptionDocument {
    val document = decodeSubscriptionDocument(text)
    val profiles = arrayListOf<AbstractBean>()
    val skippedNames = linkedSetOf<String>()
    var hasUnnamedSkipped = false
    forEachBoundedProfileLink(document, maxCandidateLinks) { link ->
        runCatching { parseExternalProfileLink(link) }
            .onSuccess(profiles::add)
            .onFailure { error ->
                if (rejectExternalRawConfig && error is ExternalRawConfigImportException) {
                    throw error
                }
                link.substringAfter('#', "").trim().takeIf(String::isNotEmpty)
                    ?.let(skippedNames::add)
                    ?: run { hasUnnamedSkipped = true }
            }
    }
    return ParsedSubscriptionDocument(
        profiles = profiles,
        skippedNames = skippedNames,
        hasUnnamedSkipped = hasUnnamedSkipped,
    )
}

private inline fun forEachBoundedProfileLink(
    text: String,
    maxCandidateLinks: Int,
    action: (String) -> Unit,
) {
    require(maxCandidateLinks > 0)
    text.requireUtf8BytesAtMost(MAX_PROFILE_IMPORT_BYTES, "Profile input")
    var physicalLineCount = 0
    var candidateCount = 0
    text.lineSequence().forEach { rawLine ->
        physicalLineCount++
        require(physicalLineCount <= MAX_PROFILE_DOCUMENT_LINES) {
            "Profile document contains too many lines"
        }
        val link = rawLine.trim()
        if (link.isEmpty()) return@forEach
        candidateCount++
        require(candidateCount <= maxCandidateLinks) {
            "Profile document contains too many links"
        }
        require(link.length <= MAX_PROFILE_LINK_CHARS) { "Profile link is too large" }
        link.requireUtf8BytesAtMost(MAX_PROFILE_LINK_CHARS, "Profile link")
        action(link)
    }
}

internal fun parseExternalProfileLink(link: String): AbstractBean {
    // The profile kind is in the URI header. Reject raw configs before their compressed payload
    // is decoded so an untrusted config link cannot consume the universal-link decompression cap.
    if (isExternalRawConfigLink(link)) throw ExternalRawConfigImportException()
    return requireSafeExternalProfile(parseSupportedProfileLink(link))
}

private fun isExternalRawConfigLink(link: String): Boolean {
    if (!link.startsWith("sn://", ignoreCase = true)) return false
    val type = link.substringAfter("://").substringBefore('?').substringBefore(':')
    return type.equals("config", ignoreCase = true)
}

internal fun requireSafeExternalProfile(profile: AbstractBean): AbstractBean {
    if (profile is ConfigBean) throw ExternalRawConfigImportException()
    return profile
}

private fun parseSupportedProfileLink(link: String): AbstractBean = when {
    link.startsWith("sn://", ignoreCase = true) -> parseUniversal(link)
    link.startsWith("vless://", ignoreCase = true) -> parseVless(link)
    link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link)
    link.startsWith("trojan://", ignoreCase = true) -> parseTrojan(link)
    link.startsWith("naive+", ignoreCase = true) -> parseNaive(link)
    link.startsWith("anytls://", ignoreCase = true) -> parseAnytls(link)
    link.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(link)
    link.startsWith("hysteria://", ignoreCase = true) ||
        link.startsWith("hysteria2://", ignoreCase = true) ||
        link.startsWith("hy2://", ignoreCase = true) -> parseHysteria(link)
    link.startsWith("tuic://", ignoreCase = true) -> parseTuic(link)
    link.startsWith("socks://", ignoreCase = true) ||
        link.startsWith("socks4://", ignoreCase = true) ||
        link.startsWith("socks4a://", ignoreCase = true) ||
        link.startsWith("socks5://", ignoreCase = true) -> parseSOCKS(link)
    link.startsWith("http://", ignoreCase = true) ||
        link.startsWith("https://", ignoreCase = true) -> parseHttp(link)
    else -> error("Unsupported node link")
}

private fun decodeSubscriptionDocument(text: String): String {
    text.requireUtf8BytesAtMost(MAX_PROFILE_IMPORT_BYTES, "Profile input")
    if (text.contains("://")) return text
    val compact = StringBuilder(minOf(text.length, 64 * 1024))
    text.forEach { char ->
        if (!char.isWhitespace()) {
            require(compact.length < MAX_BASE64_PROFILE_DOCUMENT_CHARS) {
                "Profile document is too large"
            }
            compact.append(char)
        }
    }
    if (compact.isEmpty()) return ""
    val compactPayload = compact.toString()
    val decoded = sequenceOf(
        Base64.URL_SAFE or Base64.NO_WRAP,
        Base64.DEFAULT,
    ).mapNotNull { flags -> runCatching { Base64.decode(compactPayload, flags) }.getOrNull() }
        .firstOrNull()
        ?: return text
    require(decoded.size <= MAX_PROFILE_DOCUMENT_BYTES) {
        "Profile document is too large"
    }
    return decoded.toString(Charsets.UTF_8)
}

/** Accept an optional null collection, but fail closed on malformed subscription metadata. */
internal fun subscriptionSkippedNames(result: JSONObject): JSONArray {
    require(result.has("skippedNames")) { "Subscription metadata is missing skippedNames" }
    return when (val skipped = result.opt("skippedNames")) {
        JSONObject.NULL -> JSONArray()
        is JSONArray -> skipped
        else -> error("Subscription metadata skippedNames must be an array")
    }
}

internal fun parseSerializedProfiles(encoded: String): List<AbstractBean> {
    val profiles = JSONArray(encoded)
    require(profiles.length() <= SubscriptionDataCore.MAX_SUBSCRIPTION_PROFILES) {
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
            else -> error("Unsupported serialized profile kind: $kind")
        }
        bean.initializeDefaultValues()
        bean
    }
}

internal data class NormalizedProfiles(
    val profiles: List<AbstractBean>,
    val duplicates: List<String>,
)

internal fun normalizeProfiles(
    profiles: List<AbstractBean>,
    deduplicate: Boolean,
): NormalizedProfiles {
    require(profiles.size <= SubscriptionDataCore.MAX_SUBSCRIPTION_PROFILES) {
        "Profile document contains too many profiles"
    }
    val usedNames = hashSetOf<String>()
    val nextSuffix = hashMapOf<String, Int>()
    profiles.forEach { profile ->
        val base = profile.displayNameForUi()
        var suffix = nextSuffix[base] ?: 0
        var resolved = base
        while (!usedNames.add(resolved)) {
            suffix++
            resolved = "$base ($suffix)"
        }
        nextSuffix[base] = suffix
        if (profile.name.isNotBlank() || resolved != base) profile.name = resolved
    }
    if (!deduplicate) return NormalizedProfiles(profiles, emptyList())

    val seen = linkedMapOf<String, Pair<Int, AbstractBean>>()
    val duplicates = arrayListOf<String>()
    val unique = arrayListOf<AbstractBean>()
    profiles.forEach { profile ->
        val kind = profileKind(profile).let {
            when (it) { "vless" -> "vmess"; "hysteria2" -> "hysteria"; else -> it }
        }
        val key = "$kind\u0000${profile.serverAddress}\u0000${profile.serverPort}"
        val existing = seen[key]
        if (existing == null) {
            seen[key] = unique.size to profile
            unique += profile
        } else {
            duplicates += "${profile.displayNameForUi()} (${existing.first})"
        }
    }
    return NormalizedProfiles(unique, duplicates)
}

internal const val MAX_PROFILE_DOCUMENT_BYTES = 8 * 1024 * 1024
private const val MAX_PROFILE_DOCUMENT_LINES = MAX_PROFILE_ENTRIES
internal const val MAX_BASE64_PROFILE_DOCUMENT_CHARS =
    ((MAX_PROFILE_DOCUMENT_BYTES + 2) / 3) * 4

internal fun profileKind(bean: AbstractBean): String = when (bean) {
    is ShadowTLSBean -> "shadowtls"
    is HttpBean -> "http"
    is VMessBean -> if (bean.isVLESSProfile()) "vless" else "vmess"
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
    else -> error("Unsupported profile bean: ${bean.javaClass.simpleName}")
}

internal fun encodeProfileLink(bean: AbstractBean): String {
    // Node sharing is intentionally private to NekoPilot in the simplified product.
    // This avoids maintaining another URI encoder for every proxy protocol.
    return bean.toUniversalLink()
}
