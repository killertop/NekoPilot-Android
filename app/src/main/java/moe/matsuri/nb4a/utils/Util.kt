package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import libcore.Libcore
import libcore.StringBox
import java.text.SimpleDateFormat
import java.util.*

private const val MAX_ZLIB_OUTPUT_BYTES = 32 * 1024 * 1024

object Util {

    /**
     * 取两个文本之间的文本值
     *
     * @param text  源文本 比如：欲取全文本为 12345
     * @param left  文本前面
     * @param right 后面文本
     * @return 返回 String
     */
    fun getSubString(text: String, left: String?, right: String?): String {
        var zLen: Int
        if (left.isNullOrEmpty()) {
            zLen = 0
        } else {
            zLen = text.indexOf(left)
            if (zLen > -1) {
                zLen += left.length
            } else {
                zLen = 0
            }
        }
        var yLen = if (right == null) -1 else text.indexOf(right, zLen)
        if (yLen < 0 || right.isNullOrEmpty()) {
            yLen = text.length
        }
        return text.substring(zLen, yLen)
    }

    // Base64 for all

    fun b64EncodeUrlSafe(s: String): String {
        return b64EncodeUrlSafe(s.toByteArray())
    }

    fun b64EncodeUrlSafe(b: ByteArray): String {
        return String(Base64.encode(b, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE))
    }

    // v2rayN Style
    fun b64EncodeOneLine(b: ByteArray): String {
        return String(Base64.encode(b, Base64.NO_WRAP))
    }

    fun b64EncodeDefault(b: ByteArray): String {
        return String(Base64.encode(b, Base64.DEFAULT))
    }

    fun b64Decode(b: String): ByteArray {
        var ret: ByteArray? = null

        // padding 自动处理，不用理
        // URLSafe 需要替换这两个，不要用 URL_SAFE 否则处理非 Safe 的时候会乱码
        val str = b.replace("-", "+").replace("_", "/")

        val flags = listOf(
            Base64.DEFAULT, // 多行
            Base64.NO_WRAP, // 单行
        )

        for (flag in flags) {
            try {
                ret = Base64.decode(str, flag)
            } catch (_: Exception) {
            }
            if (ret != null) return ret
        }

        throw IllegalStateException("Cannot decode base64")
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

    fun tryToSetField(o: Any, name: String, value: Any) {
        try {
            o.javaClass.getField(name).set(o, value)
        } catch (_: Exception) {
        }
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
