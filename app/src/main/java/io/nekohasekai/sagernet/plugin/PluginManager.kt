package io.nekohasekai.sagernet.plugin

import android.content.pm.ComponentInfo
import android.content.pm.ProviderInfo
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.plugin.Plugins
import java.io.File
import java.io.FileNotFoundException

object PluginManager {

    class PluginNotFoundException(val plugin: String) : FileNotFoundException(plugin),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() =
            SagerNet.application.getString(R.string.plugin_unknown, plugin)
    }

    data class InitResult(
        val path: String,
        val info: ProviderInfo,
    )

    @Throws(Throwable::class)
    fun init(pluginId: String): InitResult? {
        if (pluginId.isEmpty()) return null
        var throwable: Throwable? = null

        try {
            val result = initNative(pluginId)
            if (result != null) return result
        } catch (t: Throwable) {
            throwable = t
            Logs.w(t)
        }

        throw throwable ?: PluginNotFoundException(pluginId)
    }

    private fun initNative(pluginId: String): InitResult? {
        val info = Plugins.getPlugin(pluginId) ?: return null

        // internal so
        if (info.applicationInfo == null) {
            try {
                initNativeInternal(pluginId)?.let { return InitResult(it, info) }
            } catch (t: Throwable) {
                Logs.w("initNativeInternal failed", t)
            }
            return null
        }

        try {
            initNativeFaster(info)?.let { return InitResult(it, info) }
        } catch (t: Throwable) {
            Logs.w("initNativeFaster failed", t)
        }

        Logs.w("Init native returns empty result")
        return null
    }

    private fun initNativeInternal(pluginId: String): String? {
        fun soIfExist(soName: String): String? {
            val f = File(SagerNet.application.applicationInfo.nativeLibraryDir, soName)
            if (f.canExecute()) {
                return f.absolutePath
            }
            return null
        }
        return when (pluginId) {
            "hysteria-plugin" -> soIfExist("libhysteria.so")
            "hysteria2-plugin" -> soIfExist("libhysteria2.so")
            else -> null
        }
    }

    private fun initNativeFaster(provider: ProviderInfo): String? {
        return provider.loadString(Plugins.METADATA_KEY_EXECUTABLE_PATH)
            ?.let { relativePath ->
                resolvePluginExecutable(
                    File(provider.applicationInfo.nativeLibraryDir),
                    relativePath,
                ).absolutePath
            }
    }

    internal fun resolvePluginExecutable(nativeLibraryDir: File, relativePath: String): File {
        require(relativePath.isNotBlank())
        require(!File(relativePath).isAbsolute)
        val root = nativeLibraryDir.canonicalFile
        val executable = File(root, relativePath).canonicalFile
        require(executable.path.startsWith(root.path + File.separator))
        check(executable.isFile && executable.canExecute())
        return executable
    }

    fun ComponentInfo.loadString(key: String): String? {
        if (!metaData.containsKey(key)) return null
        metaData.getString(key)?.let { return it }
        val resourceId = metaData.getInt(key, 0)
        require(resourceId != 0) { "meta-data $key must be a string or string resource" }
        return SagerNet.application.packageManager.getResourcesForApplication(applicationInfo)
            .getString(resourceId)
    }
}
