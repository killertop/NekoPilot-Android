package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.plugin.Plugins
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {

    @Volatile lateinit var installedPackages: Map<String, PackageInfo>
    @Volatile lateinit var installedPluginPackages: Map<String, PackageInfo>
    @Volatile lateinit var installedApps: Map<String, ApplicationInfo>
    @Volatile lateinit var packageMap: Map<String, Int>
    @Volatile var uidMap: Map<Int, Set<String>> = emptyMap()
    val loaded = Mutex(true)
    var registerd = AtomicBoolean(false)

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        reload()
        app.listenForPackageChanges(false) { packageName, added ->
            if (packageName == null) reload() else updatePackage(packageName, added)
            synchronized(labelMap) { labelMap.remove(packageName) }
        }
        loaded.unlock()
    }

    // Complete package visibility is required for per-app routing and plugin discovery.
    @SuppressLint("InlinedApi", "QueryPermissionsNeeded")
    fun reload() {
        val rawPackageInfo = app.packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
                    or PackageManager.GET_META_DATA
        )

        installedPackages = rawPackageInfo.filter {
            when (it.packageName) {
                "android" -> true
                else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
            }
        }.associateBy { it.packageName }

        installedPluginPackages = rawPackageInfo.filter {
            Plugins.isExe(it)
        }.associateBy { it.packageName }

        val installed = app.packageManager.getInstalledApplications(0)
        installedApps = installed.associateBy { it.packageName }
        packageMap = installed.associate { it.packageName to it.uid }
        uidMap = installed.groupBy(ApplicationInfo::uid)
            .mapValues { (_, apps) -> apps.mapTo(linkedSetOf(), ApplicationInfo::packageName) }
    }

    @SuppressLint("InlinedApi")
    private fun updatePackage(packageName: String, added: Boolean) {
        if (!added) {
            installedPackages = installedPackages - packageName
            installedPluginPackages = installedPluginPackages - packageName
            installedApps = installedApps - packageName
            packageMap = packageMap - packageName
            uidMap = uidMap.mapValues { (_, packages) -> packages - packageName }
                .filterValues { it.isNotEmpty() }
            return
        }

        val packageInfo = runCatching {
            app.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA,
            )
        }.getOrNull() ?: return
        val applicationInfo = runCatching {
            app.packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull() ?: return
        installedPackages = if (
            packageName == "android" ||
            packageInfo.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        ) installedPackages + (packageName to packageInfo) else installedPackages - packageName
        installedPluginPackages = if (Plugins.isExe(packageInfo)) {
            installedPluginPackages + (packageName to packageInfo)
        } else installedPluginPackages - packageName
        installedApps = installedApps + (packageName to applicationInfo)
        packageMap = packageMap + (packageName to applicationInfo.uid)
        uidMap = uidMap + (
            applicationInfo.uid to (uidMap[applicationInfo.uid].orEmpty() + packageName)
        )
    }

    operator fun get(uid: Int) = uidMap[uid]
    operator fun get(packageName: String) = packageMap[packageName]

    fun awaitLoadSync() {
        if (::packageMap.isInitialized) {
            return
        }
        if (!registerd.get()) {
            register()
            return
        }
        runBlocking {
            loaded.withLock {
                // just await
            }
        }
    }

    private val labelMap = mutableMapOf<String, String>()
    fun loadLabel(packageName: String): String {
        synchronized(labelMap) { labelMap[packageName] }?.let { return it }
        val info = installedApps[packageName] ?: return packageName
        val label = info.loadLabel(app.packageManager).toString()
        synchronized(labelMap) { labelMap[packageName] = label }
        return label
    }

}
