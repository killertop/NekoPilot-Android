package moe.matsuri.nb4a.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.copyToLimited
import io.nekohasekai.sagernet.ktx.readBytesLimited
import io.nekohasekai.sagernet.ktx.sanitizeLog
import io.nekohasekai.sagernet.utils.CrashHandler
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object SendLog {
    private const val MAX_LOGCAT_EXPORT_BYTES = 16L * 1024 * 1024
    private const val MAX_NEKO_LOG_EXPORT_BYTES = 10L * 1024 * 1024

    // Create full log and send
    fun sendLog(context: Context, title: String) {
        val logFile = File.createTempFile(
            "$title ",
            ".log",
            File(app.cacheDir, "log").also { it.mkdirs() })

        var report = CrashHandler.buildReportHeader()

        report += "Logcat: \n\n"

        logFile.writeText(report)

        try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.use { input ->
                FileOutputStream(logFile, true).use { output ->
                    input.copyToLimited(output, MAX_LOGCAT_EXPORT_BYTES, "Logcat export")
                }
            }
            logFile.appendText("\n")
        } catch (e: Exception) {
            Logs.w(e)
            logFile.appendText("\nLogcat export stopped: " + CrashHandler.formatThrowable(e))
        }

        logFile.appendText("\n")
        logFile.appendBytes(getNekoLog(0))

        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/x-log")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            context, BuildConfig.APPLICATION_ID + ".cache", logFile
                        )
                    ), context.getString(R.string.abc_shareactionprovider_share_with)
            )
        )
    }

    // Get log bytes from neko.log
    fun getNekoLog(max: Long): ByteArray {
        return try {
            val file = File(
                SagerNet.application.cacheDir,
                "neko.log"
            )
            val limit = if (max > 0) {
                minOf(max, MAX_NEKO_LOG_EXPORT_BYTES)
            } else {
                MAX_NEKO_LOG_EXPORT_BYTES
            }
            val len = file.length()
            FileInputStream(file).use { stream ->
                stream.channel.position((len - limit).coerceAtLeast(0))
                stream.readBytesLimited(limit.toInt(), "Native log export")
            }
        } catch (e: Exception) {
            sanitizeLog(e.stackTraceToString()).toByteArray()
        }
    }
}
