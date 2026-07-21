package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.ktx.parseNumericAddress
import java.net.InetAddress

class Subnet(val address: InetAddress, val prefixSize: Int) {
    companion object {
        fun fromString(value: String, lengthCheck: Int = -1): Subnet? {
            val parts = value.split('/', limit = 2)
            val addr = parts[0].parseNumericAddress() ?: return null
            check(lengthCheck < 0 || addr.address.size == lengthCheck)
            return if (parts.size == 2) try {
                val prefixSize = parts[1].toInt()
                if (prefixSize < 0 || prefixSize > addr.address.size shl 3) null else Subnet(addr,
                    prefixSize)
            } catch (_: NumberFormatException) {
                null
            } else Subnet(addr, addr.address.size shl 3)
        }
    }

    private val addressLength get() = address.address.size shl 3

    init {
        require(prefixSize in 0..addressLength) { "prefixSize $prefixSize not in 0..$addressLength" }
    }

    override fun toString(): String {
        val host = requireNotNull(address.hostAddress)
        return if (prefixSize == addressLength) host else "$host/$prefixSize"
    }
}
