package io.nekohasekai.sagernet.location

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

data class ServerLocationRecord(
    val host: String,
    val resolvedIp: String,
    val countryCode: String,
    val updatedAt: Long,
)

/** Pure address and presentation rules shared by the location repository and JVM tests. */
object ServerLocationPolicy {

    private val isoCountryCodes = Locale.getISOCountries().toHashSet()

    /** Extracts the host from the address shown by [ProxyEntity] without performing DNS. */
    fun extractHost(displayAddress: String): String? {
        val address = displayAddress.trim()
        if (address.isEmpty() || address.equals("Internal", ignoreCase = true)) return null

        val extracted = when {
            "://" in address -> runCatching { URI(address).host }.getOrNull()
            address.startsWith('[') -> {
                val closingBracket = address.indexOf(']')
                if (closingBracket <= 1) return null
                val suffix = address.substring(closingBracket + 1)
                if (suffix.isNotEmpty() && !suffix.startsWith(':')) return null
                address.substring(1, closingBracket)
            }
            address.count { it == ':' } == 1 -> address.substringBefore(':')
            else -> address
        }?.trim().orEmpty()
        if (extracted.isEmpty() || extracted.any(Char::isWhitespace)) return null

        val withoutZone = extracted.substringBefore('%').removeSuffix(".")
        if (withoutZone.isEmpty()) return null
        return if (':' in withoutZone) {
            withoutZone.lowercase(Locale.ROOT)
        } else {
            runCatching {
                IDN.toASCII(withoutZone, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            }.getOrNull()?.takeIf(String::isNotEmpty)
        }
    }

    /** Returns true only for globally routable unicast addresses suitable for country lookup. */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        return when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }
    }

    /** Parses country.is single or batch responses, ignoring malformed and missing entries. */
    fun parseCountryResponse(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        val root = try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            return emptyMap()
        }
        val entries = when {
            root.isJsonArray -> root.asJsonArray.asSequence()
            root.isJsonObject -> sequenceOf(root)
            else -> emptySequence()
        }
        return buildMap {
            entries.forEach { element ->
                val item = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
                val ip = item.stringValue("ip")
                    ?.trim()
                    ?.removeSurrounding("[", "]")
                    ?.lowercase(Locale.ROOT)
                    ?.takeIf(String::isNotEmpty)
                    ?: return@forEach
                val country = item.stringValue("country")
                    ?.trim()
                    ?.uppercase(Locale.ROOT)
                    ?.takeIf(isoCountryCodes::contains)
                    ?: return@forEach
                put(ip, country)
            }
        }
    }

    fun localizedCountry(code: String, locale: Locale): String? {
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (normalized !in isoCountryCodes) return null
        return Locale.Builder()
            .setRegion(normalized)
            .build()
            .getDisplayCountry(locale)
            .trim()
            .takeIf { it.isNotEmpty() && !it.equals(normalized, ignoreCase = true) }
    }

    fun decorate(originalName: String, countryCode: String?, locale: Locale): String {
        if (originalName.isBlank() || countryCode.isNullOrBlank()) return originalName
        val country = localizedCountry(countryCode, locale) ?: return originalName
        return "$country · $originalName"
    }

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val first = bytes[0].unsigned()
        val second = bytes[1].unsigned()
        val third = bytes[2].unsigned()
        return when {
            first == 0 || first == 10 || first == 127 -> false
            first == 100 && second in 64..127 -> false // carrier-grade NAT
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 && third == 0 -> false
            first == 192 && second == 0 && third == 2 -> false // documentation
            first == 192 && second == 88 && third == 99 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false // benchmark testing
            first == 198 && second == 51 && third == 100 -> false // documentation
            first == 203 && second == 0 && third == 113 -> false // documentation
            first >= 224 -> false // multicast and reserved space
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val first = bytes[0].unsigned()
        // Globally routable unicast space is currently 2000::/3.
        if (first and 0xE0 != 0x20) return false
        // 2001:db8::/32 is reserved for documentation.
        return !(first == 0x20 && bytes[1].unsigned() == 0x01 &&
            bytes[2].unsigned() == 0x0D && bytes[3].unsigned() == 0xB8)
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF
}
