@file:Suppress("SpellCheckingInspection")

package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.BuildConfig
import java.net.Inet6Address
import java.net.InetAddress

fun String.isIpAddress(): Boolean {
    val candidate = unwrapIPV6Host()
    return candidate.isIpv4Literal() || candidate.isIpv6Literal()
}

fun String.isIpAddressV6(): Boolean {
    return unwrapIPV6Host().isIpv6Literal()
}

private fun String.isIpv4Literal(): Boolean {
    val sections = split('.')
    return sections.size == 4 && sections.all { section ->
        section.isNotEmpty() && section.length <= 3 && section.all(Char::isDigit) &&
            section.toIntOrNull() in 0..255
    }
}

private fun String.isIpv6Literal(): Boolean {
    if (isEmpty() || any { it !in "0123456789abcdefABCDEF:.%" }) return false
    return runCatching { InetAddress.getByName(this) is Inet6Address }.getOrDefault(false)
}

// [2001:4860:4860::8888] -> 2001:4860:4860::8888
fun String.unwrapIPV6Host(): String {
    if (startsWith("[") && endsWith("]")) {
        return substring(1, length - 1).unwrapIPV6Host()
    }
    return this
}

// [2001:4860:4860::8888] or 2001:4860:4860::8888 -> [2001:4860:4860::8888]
fun String.wrapIPV6Host(): String {
    val unwrapped = this.unwrapIPV6Host()
    if (unwrapped.isIpAddressV6()) {
        return "[$unwrapped]"
    } else {
        return this
    }
}

const val USER_AGENT = "NekoPilot/Android/" + BuildConfig.VERSION_NAME
