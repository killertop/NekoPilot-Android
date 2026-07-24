package io.nekohasekai.sagernet.ktx

import android.util.Log
import io.nekohasekai.sagernet.BuildConfig

object Logs {

    private const val TAG = "NekoPilot"
    private const val MAX_LOG_MESSAGE_CHARS = 16_384

    // Error messages from OkHttp/libbox can echo a request URL or a serialized node. Keep
    // diagnostics useful while ensuring that debug logcat never becomes a credential dump.
    private val uriPattern = Regex("""(?i)\b[a-z][a-z0-9+.-]{1,20}://[^\s\"'<>]+""")
    private val sensitiveAssignmentPattern = Regex(
        """(?i)([\"']?(?:password|passwd|secret|token|uuid|private[_-]?key|preSharedKey|access[_-]?token|refresh[_-]?token|authorization|proxy-authorization)[\"']?\s*[:=]\s*)(?:\"[^\"]*\"|'[^']*'|[^,;\s}\]]+)""",
    )
    private val bearerPattern = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+""")
    private val uuidPattern = Regex(
        """(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b""",
    )
    private val ipv4Pattern = Regex(
        """(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?![\w.])""",
    )

    /** Redacts user-controlled URLs and node credentials before a message reaches logcat. */
    internal fun sanitizeForLog(message: String): String {
        var sanitized = message
            .replace(uriPattern, "[redacted-url]")
            .replace(sensitiveAssignmentPattern) { match -> match.groupValues[1] + "[redacted]" }
            .replace(bearerPattern, "$1 [redacted]")
            .replace(uuidPattern, "[redacted-uuid]")
            .replace(ipv4Pattern, "[redacted-address]")
        if (sanitized.length > MAX_LOG_MESSAGE_CHARS) {
            sanitized = sanitized.take(MAX_LOG_MESSAGE_CHARS) + "…[truncated]"
        }
        return sanitized
    }

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, sanitizeForLog(message))
    }

    fun d(message: String, exception: Throwable) {
        d("$message\n${exception.stackTraceToString()}")
    }

    fun i(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, sanitizeForLog(message))
    }

    fun i(message: String, exception: Throwable) {
        i("$message\n${exception.stackTraceToString()}")
    }

    fun w(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, sanitizeForLog(message))
    }

    fun w(message: String, exception: Throwable) {
        w("$message\n${exception.stackTraceToString()}")
    }

    fun w(exception: Throwable) {
        w(exception.stackTraceToString())
    }

    fun e(message: String) {
        if (BuildConfig.DEBUG) Log.e(TAG, sanitizeForLog(message))
    }

    fun e(message: String, exception: Throwable) {
        e("$message\n${exception.stackTraceToString()}")
    }

    fun e(exception: Throwable) {
        e(exception.stackTraceToString())
    }

}
