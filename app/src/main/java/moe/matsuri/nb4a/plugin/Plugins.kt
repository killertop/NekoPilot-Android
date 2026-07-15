package moe.matsuri.nb4a.plugin

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.widget.Toast
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import java.security.MessageDigest

object Plugins {
    const val AUTHORITIES_PREFIX_SEKAI_EXE = "io.nekohasekai.sagernet.plugin."
    const val AUTHORITIES_PREFIX_NEKO_EXE = "moe.matsuri.exe."

    const val ACTION_NATIVE_PLUGIN = "io.nekohasekai.sagernet.plugin.ACTION_NATIVE_PLUGIN"

    const val METADATA_KEY_ID = "io.nekohasekai.sagernet.plugin.id"
    const val METADATA_KEY_EXECUTABLE_PATH = "io.nekohasekai.sagernet.plugin.executable_path"

    // SHA-256 certificate digests from official SagerNet/Matsuri GitHub release APKs.
    private val trustedSignerDigests = mapOf(
        "trojan-go-plugin" to setOf(
            "32250a4b5f3a6733df57a3b9ec16c38d2c7fc5f2f693a9636f8f7b3be3549641",
        ),
        "mieru-plugin" to setOf(MATSURI_PLUGIN_SIGNER),
        "naive-plugin" to setOf(MATSURI_PLUGIN_SIGNER),
        "hysteria-plugin" to setOf(MATSURI_PLUGIN_SIGNER),
    )

    fun isExe(pkg: PackageInfo): Boolean {
        if (pkg.providers?.isEmpty() == true) return false
        val provider = pkg.providers?.get(0) ?: return false
        val auth = provider.authority ?: return false
        return auth.startsWith(AUTHORITIES_PREFIX_SEKAI_EXE)
                || auth.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)
    }

    fun preferExePrefix(): String {
        return AUTHORITIES_PREFIX_NEKO_EXE
    }

    fun isUsingMatsuriExe(pluginId: String): Boolean {
        getPlugin(pluginId)?.apply {
            if (authority.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)) {
                return true
            }
        }
        return false;
    }

    fun displayExeProvider(pkgName: String): String {
        return if (pkgName.startsWith(AUTHORITIES_PREFIX_SEKAI_EXE)) {
            "SagerNet"
        } else if (pkgName.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)) {
            "Matsuri"
        } else {
            "Unknown"
        }
    }

    fun getPlugin(pluginId: String): ProviderInfo? {
        if (pluginId.isBlank()) return null
        getPluginExternal(pluginId)?.let { return it }
        // internal so
        return ProviderInfo().apply { authority = AUTHORITIES_PREFIX_NEKO_EXE }
    }

    fun getPluginExternal(pluginId: String): ProviderInfo? {
        val entry = PluginEntry.find(pluginId) ?: return null

        // try queryIntentContentProviders
        var providers = getExtPluginOld(pluginId)

        // try PackageCache
        if (providers.isEmpty()) providers = getExtPluginNew(pluginId)

        providers = providers.filter { isTrustedProvider(entry, it) }

        // not found
        if (providers.isEmpty()) return null

        if (providers.size > 1) {
            val prefer = providers.filter {
                it.authority.startsWith(preferExePrefix())
            }
            if (prefer.size == 1) providers = prefer
        }

        if (providers.size > 1) {
            val message =
                "Conflicting plugins found from: ${providers.joinToString { it.packageName }}"
            Toast.makeText(SagerNet.application, message, Toast.LENGTH_LONG).show()
        }

        return providers[0]
    }

    private fun getExtPluginNew(pluginId: String): List<ProviderInfo> {
        PackageCache.awaitLoadSync()
        val pkgs = PackageCache.installedPluginPackages
            .map { it.value }
            .filter { it.providers?.get(0)?.loadString(METADATA_KEY_ID) == pluginId }
        return pkgs.mapNotNull { it.providers?.get(0) }
    }

    private fun buildUri(id: String, auth: String) = Uri.Builder()
        .scheme("plugin")
        .authority(auth)
        .path("/$id")
        .build()

    private fun getExtPluginOld(pluginId: String): List<ProviderInfo> {
        var flags = PackageManager.GET_META_DATA
        if (Build.VERSION.SDK_INT >= 24) {
            flags =
                flags or PackageManager.MATCH_DIRECT_BOOT_UNAWARE or PackageManager.MATCH_DIRECT_BOOT_AWARE
        }
        val list1 = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(ACTION_NATIVE_PLUGIN, buildUri(pluginId, "io.nekohasekai.sagernet")), flags
        )
        val list2 = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(ACTION_NATIVE_PLUGIN, buildUri(pluginId, "moe.matsuri.lite")), flags
        )
        return (list1 + list2).mapNotNull {
            it.providerInfo
        }.filter { it.exported }
    }

    private fun isTrustedProvider(entry: PluginEntry, provider: ProviderInfo): Boolean {
        val packageName = provider.packageName ?: return false
        val digests = packageSignerDigests(packageName)
        return isTrustedPluginIdentity(
            entry.packageName,
            trustedSignerDigests[entry.pluginId].orEmpty(),
            packageName,
            digests,
        )
    }

    @Suppress("DEPRECATION")
    private fun packageSignerDigests(packageName: String): Set<String> {
        val packageManager = SagerNet.application.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = try {
            packageManager.getPackageInfo(packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return emptySet()
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            packageInfo.signatures
        }
        return signatures.orEmpty().mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    internal fun isTrustedPluginIdentity(
        expectedPackageName: String,
        trustedDigests: Set<String>,
        packageName: String,
        signerDigests: Set<String>,
    ): Boolean {
        return packageName == expectedPackageName &&
                trustedDigests.isNotEmpty() &&
                signerDigests.any { it.lowercase() in trustedDigests }
    }

    private const val MATSURI_PLUGIN_SIGNER =
        "35762758ce86a6ec297d9ccac689469bc43b9fed8ae1b27f100a86bbac00a055"
}
