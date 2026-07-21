package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.core.GoDataCore
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
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
import libcore.Libcore
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Converts Go's portable profile DTOs into Kotlin-owned persisted models. */
internal fun parseProfilesWithGo(text: String): List<AbstractBean> {
    val local = arrayListOf<AbstractBean>()
    val remaining = arrayListOf<String>()
    text.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { link ->
        val parsed = runCatching {
            when {
                link.startsWith("vless://", ignoreCase = true) -> parseVless(link)
                link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link)
                link.startsWith("trojan://", ignoreCase = true) -> parseTrojan(link)
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
                else -> null
            }
        }.onFailure { error ->
            // A malformed locally-owned URI must not be forwarded to a second
            // parser, which could interpret it with different credentials.
            if (link.substringBefore(':').lowercase() in setOf(
                    "vmess", "vless", "trojan", "anytls", "ss", "hysteria", "socks", "socks4", "socks4a", "socks5",
                )
            ) {
                throw IllegalArgumentException("Invalid node link", error)
            }
        }.getOrNull()
        if (parsed == null) remaining += link else local += parsed
    }
    if (remaining.isEmpty()) return local
    return local + parseProfileBatch(
        Libcore.parseProfileLinksBinary(remaining.joinToString("\n")),
        PROFILE_BATCH_PROFILES,
    ).profiles
}

internal fun parseProfileDocumentWithGo(text: String): List<AbstractBean> {
    return parseProfileBatch(
        Libcore.parseProfileDocumentBinary(text),
        PROFILE_BATCH_PROFILES,
    ).profiles
}

internal data class ParsedSubscriptionDocument(
    val profiles: List<AbstractBean>,
    val skippedNames: Set<String>,
    val hasUnnamedSkipped: Boolean,
)

internal fun parseSubscriptionDocumentWithGo(text: String): ParsedSubscriptionDocument {
    val result = parseProfileBatch(
        Libcore.parseSubscriptionDocumentBinary(text),
        PROFILE_BATCH_SUBSCRIPTION,
    )
    return ParsedSubscriptionDocument(
        profiles = result.profiles,
        skippedNames = result.metadata.mapNotNullTo(linkedSetOf()) {
            it.trim().takeIf(String::isNotEmpty)
        },
        hasUnnamedSkipped = result.flag,
    )
}

/** Accept an optional null collection, but fail closed on a malformed native contract. */
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
        val kind = profileKindForGo(profile).let {
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

private const val PROFILE_BATCH_MAGIC = 0x4e504231
private const val PROFILE_BATCH_VERSION = 1
private const val PROFILE_BATCH_PROFILES = 1
private const val PROFILE_BATCH_SUBSCRIPTION = 2
private const val PROFILE_BATCH_NORMALIZED = 3
private const val MAX_PROFILE_BATCH_BYTES = 32 * 1024 * 1024
private const val MAX_PROFILE_BATCH_VALUE_BYTES = 1_000_000

private data class ProfileRecord(val kind: String, val json: String)

private data class DecodedProfileBatch(
    val profiles: List<AbstractBean>,
    val metadata: List<String>,
    val flag: Boolean,
)

private fun DataInputStream.readBoundedString(): String {
    val size = readInt()
    require(size in 0..MAX_PROFILE_BATCH_VALUE_BYTES && size <= available()) {
        "Invalid profile batch value"
    }
    return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
}

private fun DataOutputStream.writeBoundedString(value: String) {
    val data = value.toByteArray(Charsets.UTF_8)
    require(data.size <= MAX_PROFILE_BATCH_VALUE_BYTES) {
        "Profile batch value is too large"
    }
    writeInt(data.size)
    write(data)
}

private fun parseProfileRecord(record: ProfileRecord): AbstractBean {
    val bean = when (record.kind) {
        "socks" -> gson.fromJson(record.json, SOCKSBean::class.java)
        "http" -> gson.fromJson(record.json, HttpBean::class.java)
        "ss" -> gson.fromJson(record.json, ShadowsocksBean::class.java)
        "vmess", "vless" -> gson.fromJson(record.json, VMessBean::class.java)
        "trojan" -> gson.fromJson(record.json, TrojanBean::class.java)
        "trojan-go" -> gson.fromJson(record.json, TrojanGoBean::class.java)
        "mieru" -> gson.fromJson(record.json, MieruBean::class.java)
        "naive" -> gson.fromJson(record.json, NaiveBean::class.java)
        "hysteria", "hysteria2" -> gson.fromJson(record.json, HysteriaBean::class.java)
        "tuic" -> gson.fromJson(record.json, TuicBean::class.java)
        "ssh" -> gson.fromJson(record.json, SSHBean::class.java)
        "wireguard" -> gson.fromJson(record.json, WireGuardBean::class.java)
        "shadowtls" -> gson.fromJson(record.json, ShadowTLSBean::class.java)
        "anytls" -> gson.fromJson(record.json, AnyTLSBean::class.java)
        "chain" -> gson.fromJson(record.json, ChainBean::class.java)
        "neko" -> gson.fromJson(record.json, NekoBean::class.java)
        "config" -> gson.fromJson(record.json, ConfigBean::class.java)
        else -> error("Unsupported Go profile kind: ${record.kind}")
    }
    bean.initializeDefaultValues()
    return bean
}

private fun parseProfileBatch(bytes: ByteArray, expectedType: Int): DecodedProfileBatch {
    require(bytes.size in 11..MAX_PROFILE_BATCH_BYTES) { "Invalid profile batch size" }
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == PROFILE_BATCH_MAGIC) { "Invalid profile batch magic" }
        require(input.readUnsignedByte() == PROFILE_BATCH_VERSION) {
            "Unsupported profile batch version"
        }
        require(input.readUnsignedByte() == expectedType) { "Unexpected profile batch type" }
        val count = input.readInt()
        require(count in 0..GoDataCore.MAX_SUBSCRIPTION_PROFILES) {
            "Profile batch contains too many profiles"
        }
        val profiles = List(count) {
            parseProfileRecord(ProfileRecord(input.readBoundedString(), input.readBoundedString()))
        }
        val metadataCount = input.readInt()
        require(metadataCount in 0..GoDataCore.MAX_SUBSCRIPTION_PROFILES) {
            "Profile batch contains too much metadata"
        }
        val metadata = List(metadataCount) { input.readBoundedString() }
        val flag = input.readUnsignedByte()
        require(flag in 0..1 && input.available() == 0) { "Invalid profile batch trailer" }
        return DecodedProfileBatch(profiles, metadata, flag == 1)
    }
}

private fun encodeProfileBatch(profiles: List<AbstractBean>): ByteArray {
    val stream = ByteArrayOutputStream()
    DataOutputStream(stream).use { output ->
        output.writeInt(PROFILE_BATCH_MAGIC)
        output.writeByte(PROFILE_BATCH_VERSION)
        output.writeByte(PROFILE_BATCH_PROFILES)
        output.writeInt(profiles.size)
        profiles.forEach { bean ->
            output.writeBoundedString(profileKindForGo(bean))
            output.writeBoundedString(gson.toJson(bean))
        }
        output.writeInt(0)
        output.writeByte(0)
    }
    return stream.toByteArray().also {
        require(it.size <= MAX_PROFILE_BATCH_BYTES) { "Profile batch is too large" }
    }
}

internal fun profileKindForGo(bean: AbstractBean): String = when (bean) {
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
    else -> error("Unsupported Go profile bean: ${bean.javaClass.simpleName}")
}

internal fun encodeProfileLinkWithGo(bean: AbstractBean): String {
    val kind = when (bean) {
        is SOCKSBean -> "socks"
        is HttpBean -> "http"
        is ShadowsocksBean -> "ss"
        is VMessBean -> if (bean.isVLESSProfile()) "vless" else "vmess"
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
