package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.ToNumberPolicy
import io.nekohasekai.sagernet.BuildConfig

/** Android/JVM helpers implemented in Kotlin. */
object JavaUtil {
    @SuppressLint("PrivateApi")
    @JvmStatic
    fun getProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val method = activityThread.getDeclaredMethod("currentProcessName")
            method.invoke(null) as String
        }.getOrDefault(BuildConfig.APPLICATION_ID)
    }

    @JvmStatic
    fun isNullOrBlank(value: String?): Boolean = value.isNullOrBlank()

    @JvmStatic
    fun isNotBlank(value: String?): Boolean = !value.isNullOrBlank()

    @JvmStatic
    fun isEmpty(array: ByteArray?): Boolean = array == null || array.isEmpty()

    @JvmField
    val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .setStrictness(Strictness.LENIENT)
        .disableHtmlEscaping()
        .create()
}
