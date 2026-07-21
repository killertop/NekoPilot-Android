package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.content.Context
import libcore.StringBox
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

private const val MAX_ZLIB_OUTPUT_BYTES = 32 * 1024 * 1024

object Util {

    fun b64EncodeUrlSafe(b: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun b64Decode(b: String): ByteArray {
        val normalized = b.trim().replace('-', '+').replace('_', '/')
        require(normalized.none(Char::isWhitespace)) { "Invalid base64 payload" }
        val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
        return try {
            Base64.getDecoder().decode(padded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid base64 payload", error)
        }
    }

    fun zlibCompress(input: ByteArray, level: Int): ByteArray {
        require(level in Deflater.NO_COMPRESSION..Deflater.BEST_COMPRESSION) { "Invalid zlib level" }
        return ByteArrayOutputStream(input.size.coerceAtLeast(32)).use { output ->
            DeflaterOutputStream(output, Deflater(level)).use { stream -> stream.write(input) }
            output.toByteArray()
        }
    }

    fun zlibDecompress(
        input: ByteArray,
        maxOutputBytes: Int = MAX_ZLIB_OUTPUT_BYTES,
    ): ByteArray {
        require(maxOutputBytes > 0)
        return InflaterInputStream(ByteArrayInputStream(input)).use { stream ->
            ByteArrayOutputStream(minOf(input.size * 2, maxOutputBytes)).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    require(output.size() <= maxOutputBytes - read) { "Zlib payload is too large" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }

    // Format Time

    @SuppressLint("SimpleDateFormat")
    val sdf1 = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    fun timeStamp2Text(t: Long): String {
        return sdf1.format(Date(t))
    }

    @SuppressLint("WrongConstant")
    fun collapseStatusBar(context: Context) {
        try {
            val statusBarManager = context.getSystemService("statusbar")
            val collapse = statusBarManager.javaClass.getMethod("collapsePanels")
            collapse.invoke(statusBarManager)
        } catch (_: Exception) {
        }
    }

    fun getStringBox(b: StringBox?): String {
        if (b != null && b.value != null) {
            return b.value
        }
        return ""
    }

}
