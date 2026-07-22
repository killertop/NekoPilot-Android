package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.useActiveVpnProxy
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.readUtf8Limited
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private const val MAX_RELEASE_METADATA_BYTES = 4 * 1024 * 1024
    private const val MAX_RELEASE_NOTES_CODE_POINTS = 6_000
    private const val MAX_RELEASE_VERSION_LENGTH = 128
    private val repositoryPattern = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun fetchLatest(): AppRelease {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("User-Agent", USER_AGENT)
            .build()
        return OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .useActiveVpnProxy()
            .build()
            .newCall(request)
            .execute().use { response ->
                check(response.isSuccessful) { "GitHub returned HTTP ${response.code}" }
                val body = response.body ?: error("GitHub returned an empty response")
                val declaredLength = body.contentLength()
                require(declaredLength < 0 || declaredLength in 1..MAX_RELEASE_METADATA_BYTES.toLong()) {
                    "Release metadata is empty or too large"
                }
                parseRelease(
                    body.byteStream().readUtf8Limited(
                        MAX_RELEASE_METADATA_BYTES,
                        "Release metadata",
                    ),
                )
            }
    }

    internal fun parseRelease(json: String): AppRelease {
        require(json.toByteArray(Charsets.UTF_8).size in 1..MAX_RELEASE_METADATA_BYTES) {
            "Release metadata is empty or too large"
        }
        require(repositoryPattern.matches(REPOSITORY)) { "Invalid GitHub repository" }
        val release = JSONObject(json)
        val tagName = release.optString("tag_name").trim()
        require(tagName.isNotEmpty() && tagName.length <= MAX_RELEASE_VERSION_LENGTH) {
            "Invalid release version"
        }
        val downloadPageUrl = release.optString("html_url").trim()
        require(isOfficialReleasePage(downloadPageUrl)) { "Invalid release download page" }
        return AppRelease(
            version = tagName.removePrefix("v").removePrefix("V"),
            notes = release.optString("body").replace("\r\n", "\n").trim()
                .takeCodePoints(MAX_RELEASE_NOTES_CODE_POINTS),
            downloadPageUrl = downloadPageUrl,
        )
    }

    private fun isOfficialReleasePage(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.rawPath.startsWith("/$REPOSITORY/releases/tag/")
    }.getOrDefault(false)
}

internal fun isRemoteVersionNewer(remote: String, current: String): Boolean =
    parseNumericVersion(remote)?.let { remoteVersion ->
        parseNumericVersion(current)?.let { currentVersion ->
            remoteVersion.parts.zip(currentVersion.parts)
                .firstOrNull { (remotePart, currentPart) -> remotePart != currentPart }
                ?.let { (remotePart, currentPart) -> remotePart > currentPart }
                ?: (!remoteVersion.preRelease && currentVersion.preRelease)
        }
    } ?: false

private data class NumericVersion(
    val parts: List<Int>,
    val preRelease: Boolean,
)

private val numericVersionPattern = Regex("(?i)^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?((?:[-+]).+)?$")

private fun parseNumericVersion(value: String): NumericVersion? {
    val match = numericVersionPattern.matchEntire(value.trim()) ?: return null
    val parts = buildList {
        for (index in 1..3) {
            val component = match.groups[index]?.value
            add(if (component.isNullOrEmpty()) 0 else component.toIntOrNull() ?: return null)
        }
    }
    return NumericVersion(parts, match.groups[4]?.value?.startsWith('-') == true)
}

private fun String.takeCodePoints(limit: Int): String {
    var end = 0
    repeat(limit) {
        if (end >= length) return substring(0, end)
        end += Character.charCount(codePointAt(end))
    }
    return substring(0, end.coerceAtMost(length))
}
