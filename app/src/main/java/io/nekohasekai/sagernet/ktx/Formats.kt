package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.parseProfiles
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
        profiles.addAll(parseProfiles(standardLinks))
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
