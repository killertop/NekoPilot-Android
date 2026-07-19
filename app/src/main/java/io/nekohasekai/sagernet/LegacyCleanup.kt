package io.nekohasekai.sagernet

import java.io.File

internal object LegacyCleanup {
    val removedPreferenceKeys = listOf(
        "enableClashAPI",
        "clashApiSecret",
        "yacdURL",
        "rulesProvider",
    )

    fun removeClashDashboardData(filesDir: File) {
        listOf("yacd", "yacd.zip", "yacd.version.txt").forEach { name ->
            File(filesDir, name).deleteRecursively()
        }
        filesDir.listFiles()
            ?.filter { it.name.startsWith("Yacd-") }
            ?.forEach(File::deleteRecursively)
        filesDir.parentFile?.listFiles()
            ?.filter { it.name == "app_webview" || it.name.startsWith("app_webview_") ||
                it.name == "app_hws_webview" || it.name.startsWith("app_hws_webview_")
            }
            ?.forEach(File::deleteRecursively)
    }
}
