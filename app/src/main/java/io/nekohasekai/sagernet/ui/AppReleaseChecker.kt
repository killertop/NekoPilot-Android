package io.nekohasekai.sagernet.ui

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.USER_AGENT
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.net.URI

data class AppRelease(
    val version: String,
    val notes: String,
    val downloadPageUrl: String,
)

/** Fetches the public release metadata only. APK download and installation remain user initiated. */
object AppReleaseChecker {

    private const val REPOSITORY = "killertop/NekoPilot-Android"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val MAX_RELEASE_NOTES_CHARS = 6_000

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
        val release = JsonParser.parseString(json).asJsonObject
        val tag = release.get("tag_name")?.asString?.trim().orEmpty()
        require(tag.isNotBlank() && tag.length <= 128) { "Invalid release version" }

        val downloadPageUrl = release.get("html_url")?.asString?.trim().orEmpty()
        val uri = runCatching { URI(downloadPageUrl) }.getOrNull()
        require(
            uri != null &&
                uri.scheme == "https" &&
                uri.host == "github.com" &&
                uri.path.startsWith("/$REPOSITORY/releases/"),
        ) { "Invalid release download page" }

        val notes = release.get("body")?.asString.orEmpty()
            .replace("\r\n", "\n")
            .trim()
            .take(MAX_RELEASE_NOTES_CHARS)

        return AppRelease(
            version = tag.removePrefix("v").removePrefix("V"),
            notes = notes,
            downloadPageUrl = downloadPageUrl,
        )
    }
}

internal fun isRemoteVersionNewer(remote: String, current: String): Boolean {
    val remoteVersion = NumericVersion.parse(remote) ?: return false
    val currentVersion = NumericVersion.parse(current) ?: return false

    for (index in 0 until 3) {
        val difference = remoteVersion.parts[index].compareTo(currentVersion.parts[index])
        if (difference != 0) return difference > 0
    }
    // A stable release supersedes a build carrying a pre-release suffix.
    return !remoteVersion.isPreRelease && currentVersion.isPreRelease
}

private data class NumericVersion(
    val parts: IntArray,
    val isPreRelease: Boolean,
) {
    companion object {
        private val pattern = Regex(
            """^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?((?:[-+]).+)?$""",
            RegexOption.IGNORE_CASE,
        )

        fun parse(value: String): NumericVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val parts = IntArray(3)
            for (index in parts.indices) {
                val component = match.groups[index + 1]?.value
                parts[index] = if (component == null) {
                    0
                } else {
                    component.toIntOrNull() ?: return null
                }
            }
            val suffix = match.groups[4]?.value.orEmpty()
            return NumericVersion(parts, suffix.startsWith('-'))
        }
    }
}
