package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ExternalRawConfigImportException
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.parseExternalProfileLink
import moe.matsuri.nb4a.utils.JavaUtil.gson
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

// JSON helpers used by Android presentation/model adapters.

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

suspend fun parseProxies(text: String): List<AbstractBean> {
    val links = tokenizeProfileImportLinks(text)

    links.firstOrNull {
        it.startsWith("clash://install-config?", ignoreCase = true) ||
            it.startsWith("sn://subscription?", ignoreCase = true)
    }?.let { throw SubscriptionFoundException(it) }

    val profiles = ArrayList<AbstractBean>()
    links.filter { it.startsWith("sn://", ignoreCase = true) }.forEach { link ->
        try {
            profiles.add(parseExternalProfileLink(link).applyDefaultValues())
        } catch (error: ExternalRawConfigImportException) {
            throw error
        } catch (error: Exception) {
            Logs.d("Universal link rejected (${error.javaClass.simpleName})")
        }
    }
    links.filterNot { it.startsWith("sn://", ignoreCase = true) }.forEach { link ->
        profiles += parseExternalProfileLink(link)
    }
    if (profiles.isNotEmpty()) return profiles

    links.firstOrNull {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }?.let { link ->
        val clashUrl = HttpUrl.Builder()
            .scheme("https")
            .host("install-config")
            .addQueryParameter("url", link)
            .build()
            .toString()
            .replaceFirst("https://", "clash://")
        throw SubscriptionFoundException(clashUrl)
    }
    return emptyList()
}

private fun tokenizeProfileImportLinks(text: String): List<String> {
    text.requireUtf8BytesAtMost(MAX_PROFILE_IMPORT_BYTES, "Profile list")
    val links = ArrayList<String>()
    var tokenStart = -1
    var physicalLineCount = 1

    fun addToken(endExclusive: Int) {
        if (tokenStart < 0) return
        require(links.size < MAX_PROFILE_ENTRIES) { "Profile list contains too many links" }
        require(endExclusive - tokenStart <= MAX_PROFILE_LINK_CHARS) { "Profile link is too large" }
        val link = text.substring(tokenStart, endExclusive)
        link.requireUtf8BytesAtMost(MAX_PROFILE_LINK_CHARS, "Profile link")
        links += link
        tokenStart = -1
    }

    text.forEachIndexed { index, char ->
        if (char == '\n') {
            physicalLineCount++
            require(physicalLineCount <= MAX_PROFILE_ENTRIES) {
                "Profile list contains too many lines"
            }
        }
        if (char.isWhitespace()) {
            addToken(index)
        } else if (tokenStart < 0) {
            tokenStart = index
        }
    }
    addToken(text.length)
    return links
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}
