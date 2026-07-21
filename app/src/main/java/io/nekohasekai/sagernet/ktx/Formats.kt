package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.parseProfilesWithGo
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

// JSON & Base64

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

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

// wtf hutool
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}


// 重名了喵
fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}


fun String.decodeBase64UrlSafe(): String {
    return String(Util.b64Decode(this))
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

suspend fun parseProxies(text: String): List<AbstractBean> {
    require(text.length <= MAX_PROFILE_IMPORT_BYTES) { "Profile list is too large" }
    val links = text.lineSequence()
        .flatMap { it.trim().splitToSequence(' ') }
        .filter { it.isNotEmpty() }
        .toList()
    require(links.size <= MAX_PROFILE_ENTRIES) { "Profile list contains too many links" }
    require(links.all { it.length <= MAX_PROFILE_LINK_CHARS }) { "Profile link is too large" }

    links.firstOrNull {
        it.startsWith("clash://install-config?") || it.startsWith("sn://subscription?")
    }?.let { throw SubscriptionFoundException(it) }

    val profiles = ArrayList<AbstractBean>()
    links.filter { it.startsWith("sn://") }.forEach { link ->
        runCatching { parseUniversal(link) }
            .onSuccess { profiles.add(it.applyDefaultValues()) }
            .onFailure { Logs.d("Universal link rejected (${it.javaClass.simpleName})") }
    }
    val standardLinks = links.filterNot { it.startsWith("sn://") }.joinToString("\n")
    if (standardLinks.isNotBlank()) {
        profiles.addAll(parseProfilesWithGo(standardLinks))
    }
    if (profiles.isNotEmpty()) return profiles

    links.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }?.let { link ->
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

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}
