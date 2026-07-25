package io.nekohasekai.sagernet.group

import android.content.Context
import android.text.format.DateUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Stable categories used for both the immediate UI message and persisted diagnostics. */
internal enum class SubscriptionFailureKind(
    val key: String,
    val messageRes: Int,
) {
    URL("url", R.string.subscription_update_url_error),
    SECURITY("security", R.string.subscription_update_security_error),
    DNS("dns", R.string.subscription_update_dns_error),
    TIMEOUT("timeout", R.string.subscription_update_timeout_error),
    AUTH("auth", R.string.subscription_update_auth_error),
    HTTP("http", R.string.subscription_update_http_error),
    NETWORK("network", R.string.subscription_update_network_error),
    NO_NODES("no_nodes", R.string.subscription_update_no_nodes_error),
    FORMAT("format", R.string.subscription_update_format_error),
    RESPONSE("response", R.string.subscription_update_response_error),
    UNKNOWN("unknown", R.string.subscription_update_failed),
    ;

    companion object {
        fun fromKey(value: String): SubscriptionFailureKind =
            entries.firstOrNull { it.key == value } ?: UNKNOWN
    }
}

internal open class SubscriptionUpdateException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal class SubscriptionUrlException(message: String) :
    SubscriptionUpdateException(message)

internal class SubscriptionSecurityException(message: String) :
    SubscriptionUpdateException(message)

internal class SubscriptionHttpException(
    val statusCode: Int,
    val contentType: String,
    val responseBytes: Long,
) : SubscriptionUpdateException("Subscription returned HTTP $statusCode")

internal class SubscriptionNoNodesException(
    val contentType: String = "",
    val responseBytes: Long = -1L,
) : SubscriptionUpdateException("No supported proxy nodes found in subscription response")

internal class SubscriptionFormatException(
    message: String,
    val contentType: String = "",
    val responseBytes: Long = -1L,
) : SubscriptionUpdateException(message)

internal class SubscriptionResponseException(
    message: String,
    val contentType: String = "",
    val responseBytes: Long = -1L,
) : SubscriptionUpdateException(message)

internal data class SubscriptionFailureRecord(
    val kind: SubscriptionFailureKind,
    val technicalMessage: String,
    val occurredAtSeconds: Long,
    val httpStatus: Int = 0,
    val contentType: String = "",
    val responseBytes: Long = -1L,
) {
    fun userMessage(context: Context): String = when (kind) {
        SubscriptionFailureKind.AUTH -> context.getString(
            R.string.subscription_update_auth_error,
            httpStatus.takeIf { it > 0 } ?: 401,
        )
        SubscriptionFailureKind.HTTP -> context.getString(
            R.string.subscription_update_http_error,
            httpStatus.takeIf { it > 0 } ?: 0,
        )
        else -> context.getString(kind.messageRes)
    }

    fun diagnosticText(context: Context): String = buildString {
        append(userMessage(context))
        append('\n')
        append(
            context.getString(
                R.string.subscription_update_diagnostic_time,
                DateUtils.formatDateTime(
                    context,
                    occurredAtSeconds * 1000L,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                ),
            ),
        )
        append('\n')
        append(context.getString(R.string.subscription_update_diagnostic_stage, kind.key))
        if (httpStatus > 0) {
            append('\n')
            append(context.getString(R.string.subscription_update_diagnostic_http, httpStatus))
        }
        if (contentType.isNotBlank()) {
            append('\n')
            append(context.getString(R.string.subscription_update_diagnostic_content_type, contentType))
        }
        if (responseBytes >= 0L) {
            append('\n')
            append(
                context.getString(
                    R.string.subscription_update_diagnostic_size,
                    responseBytes,
                ),
            )
        }
        if (technicalMessage.isNotBlank()) {
            append('\n')
            append(technicalMessage)
        }
    }
}

internal fun subscriptionFailureKind(error: Throwable): SubscriptionFailureKind {
    val root = error.rootSubscriptionCause()
    return when {
        root is SubscriptionUrlException -> SubscriptionFailureKind.URL
        root is SubscriptionSecurityException -> SubscriptionFailureKind.SECURITY
        root is SubscriptionHttpException && root.statusCode in 401..403 ->
            SubscriptionFailureKind.AUTH
        root is SubscriptionHttpException -> SubscriptionFailureKind.HTTP
        root is SubscriptionNoNodesException -> SubscriptionFailureKind.NO_NODES
        root is SubscriptionFormatException -> SubscriptionFailureKind.FORMAT
        root is SubscriptionResponseException -> SubscriptionFailureKind.RESPONSE
        root is UnknownHostException -> SubscriptionFailureKind.DNS
        root is SocketTimeoutException -> SubscriptionFailureKind.TIMEOUT
        root is SSLException -> SubscriptionFailureKind.NETWORK
        else -> {
            val reason = buildString {
                append(root.javaClass.simpleName.lowercase())
                append(' ')
                append(root.message.orEmpty().lowercase())
            }
            when {
                listOf("unknownhost", "no such host", "name resolution", "dns")
                    .any(reason::contains) -> SubscriptionFailureKind.DNS
                listOf("timeout", "timed out", "deadline exceeded", "no recent network activity")
                    .any(reason::contains) -> SubscriptionFailureKind.TIMEOUT
                listOf("private or reserved", "must use https", "credentials")
                    .any(reason::contains) -> SubscriptionFailureKind.SECURITY
                listOf("malformed http", "bad response", "eof")
                    .any(reason::contains) -> SubscriptionFailureKind.NETWORK
                listOf("status code", "returned http")
                    .any(reason::contains) -> SubscriptionFailureKind.HTTP
                listOf("no proxies", "unsupported profile", "profile document", "decode", "parse")
                    .any(reason::contains) -> SubscriptionFailureKind.FORMAT
                root is IOException || listOf("connection", "network", "socket", "eof", "tls", "ssl")
                    .any(reason::contains) -> SubscriptionFailureKind.NETWORK
                else -> SubscriptionFailureKind.UNKNOWN
            }
        }
    }
}

internal fun subscriptionFailureUserMessage(error: Throwable): String {
    val root = error.rootSubscriptionCause()
    val kind = subscriptionFailureKind(error)
    return when (kind) {
        SubscriptionFailureKind.AUTH -> app.getString(
            R.string.subscription_update_auth_error,
            (root as? SubscriptionHttpException)?.statusCode ?: 401,
        )
        SubscriptionFailureKind.HTTP -> app.getString(
            R.string.subscription_update_http_error,
            (root as? SubscriptionHttpException)?.statusCode ?: 0,
        )
        else -> app.getString(kind.messageRes)
    }
}

internal fun buildSubscriptionFailureRecord(
    error: Throwable,
    subscriptionLink: String?,
    nowSeconds: Long = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()),
): SubscriptionFailureRecord {
    val root = error.rootSubscriptionCause()
    return SubscriptionFailureRecord(
        kind = subscriptionFailureKind(error),
        technicalMessage = sanitizeSubscriptionError(error.readableMessage, subscriptionLink),
        occurredAtSeconds = nowSeconds,
        httpStatus = (root as? SubscriptionHttpException)?.statusCode ?: 0,
        contentType = root.subscriptionContentType(),
        responseBytes = root.subscriptionResponseBytes(),
    )
}

private fun Throwable.rootSubscriptionCause(): Throwable {
    var current = this
    val seen = HashSet<Throwable>()
    while (current.cause != null && seen.add(current)) current = current.cause!!
    return current
}

private fun Throwable.subscriptionContentType(): String = when (this) {
    is SubscriptionHttpException -> contentType
    is SubscriptionNoNodesException -> contentType
    is SubscriptionFormatException -> contentType
    is SubscriptionResponseException -> contentType
    else -> ""
}.sanitizeContentType()

private fun Throwable.subscriptionResponseBytes(): Long = when (this) {
    is SubscriptionHttpException -> responseBytes
    is SubscriptionNoNodesException -> responseBytes
    is SubscriptionFormatException -> responseBytes
    is SubscriptionResponseException -> responseBytes
    else -> -1L
}

private fun String.sanitizeContentType(): String {
    val mediaType = trim().substringBefore(';').trim().lowercase(Locale.ROOT)
    return if (mediaType.matches(Regex("[a-z0-9!#%&'*+.^_`|~-]+/[a-z0-9!#%&'*+.^_`|~-]+"))) {
        mediaType.take(128)
    } else {
        ""
    }
}

/** A private, atomic, cross-process sidecar for the most recent failed update. */
internal class SubscriptionDiagnosticsFileStore(
    private val directory: File,
) {
    fun write(groupId: Long, record: SubscriptionFailureRecord) {
        require(groupId > 0L) { "Subscription group ID must be positive" }
        directory.mkdirs()
        val properties = Properties().apply {
            setProperty("kind", record.kind.key)
            setProperty("technicalMessage", record.technicalMessage.take(MAX_TECHNICAL_MESSAGE))
            setProperty("occurredAtSeconds", record.occurredAtSeconds.toString())
            setProperty("httpStatus", record.httpStatus.toString())
            setProperty("contentType", record.contentType.take(MAX_CONTENT_TYPE))
            setProperty("responseBytes", record.responseBytes.toString())
        }
        val target = fileFor(groupId)
        val temporary = File.createTempFile(".$groupId-", ".tmp", directory)
        try {
            temporary.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                properties.store(writer, null)
            }
            check(temporary.renameTo(target)) { "Unable to publish subscription diagnostics" }
        } finally {
            temporary.delete()
        }
    }

    fun read(groupId: Long): SubscriptionFailureRecord? {
        if (groupId <= 0L) return null
        val file = fileFor(groupId)
        if (!file.isFile) return null
        val record = runCatching {
            val properties = Properties()
            file.inputStream().bufferedReader(Charsets.UTF_8).use { properties.load(it) }
            val occurredAt = properties.getProperty("occurredAtSeconds")?.toLongOrNull()
                ?: return@runCatching null
            val kind = SubscriptionFailureKind.fromKey(properties.getProperty("kind").orEmpty())
            SubscriptionFailureRecord(
                kind = kind,
                technicalMessage = properties.getProperty("technicalMessage").orEmpty()
                    .take(MAX_TECHNICAL_MESSAGE),
                occurredAtSeconds = occurredAt,
                httpStatus = properties.getProperty("httpStatus")?.toIntOrNull()?.coerceIn(0, 599)
                    ?: 0,
                contentType = properties.getProperty("contentType").orEmpty()
                    .sanitizeContentType(),
                responseBytes = properties.getProperty("responseBytes")?.toLongOrNull()
                    ?.coerceIn(-1L, MAX_RESPONSE_BYTES) ?: -1L,
            )
        }.getOrNull()
        if (record == null) file.delete()
        return record
    }

    fun clear(groupId: Long) {
        if (groupId > 0L) fileFor(groupId).delete()
    }

    private fun fileFor(groupId: Long) = File(directory, "$groupId.properties")

    private companion object {
        const val MAX_TECHNICAL_MESSAGE = 500
        const val MAX_CONTENT_TYPE = 128
        const val MAX_RESPONSE_BYTES = 100L * 1024L * 1024L
    }
}

internal object SubscriptionDiagnosticsStore {
    private const val DIRECTORY = "subscription-diagnostics"

    private fun store() = SubscriptionDiagnosticsFileStore(File(app.filesDir, DIRECTORY))

    fun write(groupId: Long, record: SubscriptionFailureRecord) = store().write(groupId, record)

    fun read(groupId: Long): SubscriptionFailureRecord? = store().read(groupId)

    fun clear(groupId: Long) = store().clear(groupId)
}
