package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.content.Context
import libcore.Libcore
import libcore.StringBox
import java.text.SimpleDateFormat
import java.util.*

private const val MAX_ZLIB_OUTPUT_BYTES = 32 * 1024 * 1024

object Util {

    fun b64EncodeUrlSafe(b: ByteArray): String {
        return Libcore.base64EncodeURLSafe(b)
    }

    fun b64Decode(b: String): ByteArray {
        return Libcore.base64DecodeFlexible(b)
    }

    fun zlibCompress(input: ByteArray, level: Int): ByteArray =
        Libcore.zlibCompress(input, level)

    fun zlibDecompress(
        input: ByteArray,
        maxOutputBytes: Int = MAX_ZLIB_OUTPUT_BYTES,
    ): ByteArray {
        require(maxOutputBytes > 0)
        return Libcore.zlibDecompress(input, maxOutputBytes.toLong())
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
