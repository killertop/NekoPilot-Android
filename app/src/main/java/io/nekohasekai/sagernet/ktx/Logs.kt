package io.nekohasekai.sagernet.ktx

import libcore.Libcore

private const val MAX_LOG_MESSAGE_CHARS = 64 * 1024
private val proxyLinkPattern = Regex(
    "(?i)\\b(?:sn|ss|ssr|socks4a?|socks5|vmess|vless|trojan(?:-go)?|naive\\+(?:https|quic)|hysteria2?|hy2|tuic|anytls)://[^\\s]+"
)
private val uriUserInfoPattern = Regex("([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s]+@")
private val querySecretPattern = Regex(
    "(?i)([?&](?:password|passwd|token|secret|auth|key|uuid|psk)=)[^&\\s]+"
)
private val structuredSecretPattern = Regex(
    "(?i)([\"']?(?:password|passwd|token|secret|private[_-]?key|auth|uuid|psk)[\"']?\\s*[:=]\\s*[\"']?)[^\"',\\s}\\]]+"
)

internal fun sanitizeLog(message: String): String {
    val sanitized = message
        .replace(proxyLinkPattern, "[redacted proxy link]")
        .replace(uriUserInfoPattern, "$1[redacted]@")
        .replace(querySecretPattern, "$1[redacted]")
        .replace(structuredSecretPattern, "$1[redacted]")
    return if (sanitized.length <= MAX_LOG_MESSAGE_CHARS) sanitized else {
        sanitized.take(MAX_LOG_MESSAGE_CHARS) + "\n[log entry truncated]"
    }
}

object Logs {

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // level int use logrus.go

    fun d(message: String) {
        Libcore.nekoLogPrintln(sanitizeLog("[Debug] [${mkTag()}] $message"))
    }

    fun d(message: String, exception: Throwable) {
        d("$message\n${exception.stackTraceToString()}")
    }

    fun i(message: String) {
        Libcore.nekoLogPrintln(sanitizeLog("[Info] [${mkTag()}] $message"))
    }

    fun i(message: String, exception: Throwable) {
        i("$message\n${exception.stackTraceToString()}")
    }

    fun w(message: String) {
        Libcore.nekoLogPrintln(sanitizeLog("[Warning] [${mkTag()}] $message"))
    }

    fun w(message: String, exception: Throwable) {
        w("$message\n${exception.stackTraceToString()}")
    }

    fun w(exception: Throwable) {
        w(exception.stackTraceToString())
    }

    fun e(message: String) {
        Libcore.nekoLogPrintln(sanitizeLog("[Error] [${mkTag()}] $message"))
    }

    fun e(message: String, exception: Throwable) {
        e("$message\n${exception.stackTraceToString()}")
    }

    fun e(exception: Throwable) {
        e(exception.stackTraceToString())
    }

}
