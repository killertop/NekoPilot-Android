package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import kotlin.math.abs

/** Android presentation for persisted compatibility beans; protocol semantics remain in Go. */
internal fun AbstractBean.displayNameForUi(): String = when {
    name.isNotBlank() -> name
    this is ChainBean -> "Chain ${abs(hashCode())}"
    this is ConfigBean -> "Custom ${abs(hashCode())}"
    else -> displayAddressForUi()
}

internal fun AbstractBean.displayAddressForUi(): String = when (this) {
    is ChainBean, is ConfigBean -> "Internal"
    is HysteriaBean -> "${serverAddress.wrapIPV6Host()}:$serverPorts"
    else -> "${serverAddress.wrapIPV6Host()}:$serverPort"
}

internal fun SOCKSBean.protocolNameForUi(): String = when (protocol) {
    SOCKSBean.PROTOCOL_SOCKS4 -> "SOCKS4"
    SOCKSBean.PROTOCOL_SOCKS4A -> "SOCKS4A"
    else -> "SOCKS5"
}

internal fun ConfigBean.displayTypeForUi(): String {
    if (type == 1 && config.isNotBlank()) {
        runCatching {
            gson.fromJson(config, JsonObject::class.java)
                ?.get("type")
                ?.asString
                ?.takeIf(String::isNotBlank)
        }.getOrNull()?.let { return "$it (sing-box)" }
    }
    return if (type == 0) "sing-box config" else "sing-box outbound"
}

internal fun StandardV2RayBean.isVLESSProfile(): Boolean =
    this is VMessBean && alterId == -1
