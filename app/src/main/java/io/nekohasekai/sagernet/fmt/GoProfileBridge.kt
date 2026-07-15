package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.database.DataStore
import libcore.Libcore
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.json.JSONArray

/** Converts Go's portable profile DTOs into the existing persisted bean ABI. */
internal fun parseProfilesWithGo(text: String): List<AbstractBean> {
    return parseGoProfiles(Libcore.parseProfileLinks(text))
}

internal fun parseProfileDocumentWithGo(text: String): List<AbstractBean> {
    return parseGoProfiles(Libcore.parseProfileDocument(text))
}

private fun parseGoProfiles(encoded: String): List<AbstractBean> {
    val profiles = JSONArray(encoded)
    return List(profiles.length()) { index ->
        val profile = profiles.getJSONObject(index)
        val bean = when (val kind = profile.getString("kind")) {
            "socks" -> gson.fromJson(profile.toString(), SOCKSBean::class.java)
            "http" -> gson.fromJson(profile.toString(), HttpBean::class.java)
            "ss" -> gson.fromJson(profile.toString(), ShadowsocksBean::class.java)
            "vmess" -> gson.fromJson(profile.toString(), VMessBean::class.java)
            "trojan" -> gson.fromJson(profile.toString(), TrojanBean::class.java)
            "trojan-go" -> gson.fromJson(profile.toString(), TrojanGoBean::class.java)
            "naive" -> gson.fromJson(profile.toString(), NaiveBean::class.java)
            "hysteria" -> gson.fromJson(profile.toString(), HysteriaBean::class.java)
            "tuic" -> gson.fromJson(profile.toString(), TuicBean::class.java)
            "anytls" -> gson.fromJson(profile.toString(), AnyTLSBean::class.java)
            "config" -> gson.fromJson(profile.toString(), ConfigBean::class.java)
            else -> error("Unsupported Go profile kind: $kind")
        }
        bean.initializeDefaultValues()
        bean
    }
}

internal fun buildProfileOutboundWithGo(bean: AbstractBean): CustomSingBoxOption {
    val kind = when (bean) {
        is ShadowTLSBean -> "shadowtls"
        is HttpBean -> "http"
        is VMessBean -> if (bean.isVLESS) "vless" else "vmess"
        is TrojanBean -> "trojan"
        is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria" else "hysteria2"
        is TuicBean -> "tuic"
        is SOCKSBean -> "socks"
        is ShadowsocksBean -> "ss"
        is SSHBean -> "ssh"
        is AnyTLSBean -> "anytls"
        else -> error("Unsupported Go outbound bean: ${bean.javaClass.simpleName}")
    }
    return CustomSingBoxOption(
        Libcore.buildProfileOutbound(kind, gson.toJson(bean), DataStore.globalAllowInsecure)
    )
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
