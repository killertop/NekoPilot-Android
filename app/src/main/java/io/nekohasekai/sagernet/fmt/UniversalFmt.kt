package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import moe.matsuri.nb4a.utils.Util

internal const val MAX_EXTERNAL_UNIVERSAL_PAYLOAD_CHARS = 48 * 1024
internal const val MAX_EXTERNAL_UNIVERSAL_DECOMPRESSED_BYTES = 1 * 1024 * 1024

fun parseUniversal(link: String): AbstractBean = parseUniversal(link, external = false)

/** Applies a stricter resource budget to payloads arriving from an untrusted link. */
internal fun parseExternalUniversal(link: String): AbstractBean = parseUniversal(link, external = true)

private fun parseUniversal(link: String, external: Boolean): AbstractBean {
    return if (link.contains("?")) {
        val type = link.substringAfter("sn://").substringBefore("?")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArrayStrict(
                decodeUniversalPayload(
                    encodedPayload = link.substringAfter("?"),
                    compressed = true,
                    external = external,
                ),
            )
        }.requireBean()
    } else {
        val type = link.substringAfter("sn://").substringBefore(":")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArrayStrict(
                decodeUniversalPayload(
                    encodedPayload = link.substringAfter(":").substringAfter(":"),
                    compressed = false,
                    external = external,
                ),
            )
        }.requireBean()
    }
}

private fun decodeUniversalPayload(
    encodedPayload: String,
    compressed: Boolean,
    external: Boolean,
): ByteArray {
    val normalizedPayload = encodedPayload.trim()
    if (external) {
        require(normalizedPayload.length <= MAX_EXTERNAL_UNIVERSAL_PAYLOAD_CHARS) {
            "Universal link payload is too large"
        }
    }
    val payload = Util.b64Decode(normalizedPayload)
    return if (compressed) {
        if (external) {
            Util.zlibDecompress(payload, MAX_EXTERNAL_UNIVERSAL_DECOMPRESSED_BYTES)
        } else {
            Util.zlibDecompress(payload)
        }
    } else {
        payload
    }
}

fun AbstractBean.toUniversalLink(): String {
    var link = "sn://"
    link += TypeMap.reversed[ProxyEntity().putBean(this).type]
    link += "?"
    link += Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(this), 9))
    return link
}


fun ProxyGroup.toUniversalLink(): String {
    var link = "sn://subscription?"
    export = true
    link += Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(this), 9))
    export = false
    return link
}
