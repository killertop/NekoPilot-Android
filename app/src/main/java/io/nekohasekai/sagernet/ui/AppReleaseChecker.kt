package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.USER_AGENT
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

data class AppRelease(
    val version: String,
    val notes: String,
    val downloadPageUrl: String,
)

/** Fetches the public release metadata only. APK download and installation remain user initiated. */
object AppReleaseChecker {

    private const val REPOSITORY = "killertop/NekoPilot-Android"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"

    fun fetchLatest(): AppRelease {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            setTimeout(20_000)
            // Use the active NekoPilot tunnel when possible, then fall back to direct access.
            trySocks5(
                DataStore.mixedPort,
                DataStore.mixedProxyUsername,
                DataStore.mixedProxyPassword,
            )
        }
        return try {
            val response = client.newRequest().apply {
                setURL(LATEST_RELEASE_URL)
                setUserAgent(USER_AGENT)
            }.execute()
            parseRelease(Util.getStringBox(response.contentString))
        } finally {
            client.close()
        }
    }

    internal fun parseRelease(json: String): AppRelease {
        val release = JSONObject(Libcore.parseAppRelease(json, REPOSITORY))
        return AppRelease(
            version = release.getString("version"),
            notes = release.getString("notes"),
            downloadPageUrl = release.getString("download_page_url"),
        )
    }
}

internal fun isRemoteVersionNewer(remote: String, current: String): Boolean =
    Libcore.isRemoteVersionNewer(remote, current)
