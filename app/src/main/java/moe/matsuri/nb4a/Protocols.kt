package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import moe.matsuri.nb4a.proxy.config.ConfigBean

internal enum class ConnectionFailureCategory {
    TIMEOUT,
    RESET,
    OTHER,
}

internal fun connectionFailureCategory(message: String): ConnectionFailureCategory {
    val normalized = message.lowercase()
    return when {
        normalized.contains("timeout") || normalized.contains("deadline") -> {
            ConnectionFailureCategory.TIMEOUT
        }

        normalized.contains("refused") ||
            normalized.contains("closed pipe") ||
            normalized.contains("closed network connection") ||
            normalized.contains("connection reset") ||
            normalized == "eof" -> {
            ConnectionFailureCategory.RESET
        }

        else -> ConnectionFailureCategory.OTHER
    }
}

// Settings for all protocols, built-in or plugin
object Protocols {

    // Deduplication

    class Deduplication(
        val bean: AbstractBean, val type: String
    ) {

        fun hash(): String {
            if (bean is ConfigBean) {
                return bean.config
            }
            return bean.serverAddress + bean.serverPort + type
        }

        override fun hashCode(): Int {
            return hash().toByteArray().contentHashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Deduplication

            return hash() == other.hash()
        }

    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return when (type) {
            TYPE_NEKO -> getColorAttr(android.R.attr.textColorPrimary)
            else -> getColorAttr(R.attr.accentOrTextSecondary)
        }
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        return when (connectionFailureCategory(msg)) {
            ConnectionFailureCategory.TIMEOUT -> {
                app.getString(R.string.connection_test_timeout)
            }

            ConnectionFailureCategory.RESET -> {
                app.getString(R.string.connection_test_refused)
            }

            ConnectionFailureCategory.OTHER -> msg
        }
    }

}
