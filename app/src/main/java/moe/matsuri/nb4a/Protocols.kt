package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr

internal enum class ConnectionFailureCategory {
    TIMEOUT,
    RESET,
    OTHER,
}

internal fun connectionFailureCategory(message: String): ConnectionFailureCategory {
    val normalized = message.lowercase()
    return when {
        "timeout" in normalized || "deadline" in normalized -> ConnectionFailureCategory.TIMEOUT
        "refused" in normalized ||
            "closed pipe" in normalized ||
            "closed network connection" in normalized ||
            "connection reset" in normalized ||
            normalized == "eof" -> ConnectionFailureCategory.RESET
        else -> ConnectionFailureCategory.OTHER
    }
}

// Settings for all protocols, built-in or plugin
object Protocols {

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
