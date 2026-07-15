package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
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

fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    try {
        JSONObject(j as String)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
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
    val linksByLine = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    require(linksByLine.size <= MAX_PROFILE_ENTRIES) { "Profile list contains too many lines" }
    require(linksByLine.all { it.length <= MAX_PROFILE_LINK_CHARS }) { "Profile link is too large" }
    val links = linksByLine.flatMap { line -> line.split(' ').filter { it.isNotEmpty() } }
    require(links.size <= MAX_PROFILE_ENTRIES) { "Profile list contains too many links" }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()

    fun String.parseLink(entities: ArrayList<AbstractBean>) {
        if (startsWith("clash://install-config?") || startsWith("sn://subscription?")) {
            throw SubscriptionFoundException(this)
        }

        if (startsWith("sn://")) {
            Logs.d("Try parse universal link")
            runCatching {
                entities.add(parseUniversal(this))
            }.onFailure {
                Logs.d("Universal link rejected (${it.javaClass.simpleName})")
            }
        } else if (startsWith("socks://") || startsWith("socks4://") || startsWith("socks4a://") || startsWith(
                "socks5://"
            )
        ) {
            Logs.d("Try parse SOCKS link")
            runCatching {
                entities.add(parseSOCKS(this))
            }.onFailure {
                Logs.d("SOCKS link rejected (${it.javaClass.simpleName})")
            }
        } else if (matches("(http|https)://.*".toRegex())) {
            Logs.d("Try parse HTTP link")
            runCatching {
                entities.add(parseHttp(this))
            }.onFailure {
                Logs.d("HTTP link rejected (${it.javaClass.simpleName})")
                val clashUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host("install-config")
                    .addQueryParameter("url", this)
                    .build()
                    .toString()
                    .replaceFirst("https://", "clash://")
                throw (SubscriptionFoundException(clashUrl))
            }
        } else if (startsWith("vmess://")) {
            Logs.d("Try parse VMess link")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.d("VMess link rejected (${it.javaClass.simpleName})")
            }
        } else if (startsWith("vless://")) {
            Logs.d("Try parse VLESS link")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.d("VLESS link rejected (${it.javaClass.simpleName})")
            }
        } else if (startsWith("trojan://")) {
            Logs.d("Try parse Trojan link")
            runCatching {
                entities.add(parseTrojan(this))
            }.onFailure {
                Logs.d("Trojan link rejected (${it.javaClass.simpleName})")
            }
        } else if (startsWith("trojan-go://")) {
            Logs.d("Try parse Trojan-Go link")
            runCatching {
                entities.add(parseTrojanGo(this))
            }.onFailure {
                Logs.d("Trojan-Go link rejected (${it.javaClass.simpleName})")
            }
        } else if (startsWith("ss://")) {
            Logs.d("Try parse Shadowsocks link")
            runCatching {
                entities.add(parseShadowsocks(this))
            }.onFailure {
                Logs.d("Shadowsocks link rejected (${it.javaClass.simpleName})")
            }
        } else if (startsWith("naive+")) {
            Logs.d("Try parse Naive link")
            runCatching {
                entities.add(parseNaive(this))
            }.onFailure {
                Logs.d("Naive link rejected (${it.javaClass.simpleName})")
            }
        } else if (startsWith("hysteria://")) {
            Logs.d("Try parse Hysteria link")
            runCatching {
                entities.add(parseHysteria1(this))
            }.onFailure {
                Logs.d("Hysteria link rejected (${it.javaClass.simpleName})")
            }
        } else if (startsWith("hysteria2://") || startsWith("hy2://")) {
            Logs.d("Try parse Hysteria 2 link")
            runCatching {
                entities.add(parseHysteria2(this))
            }.onFailure {
                Logs.d("Hysteria 2 link rejected (${it.javaClass.simpleName})")
            }
        } else if (startsWith("tuic://")) {
            Logs.d("Try parse TUIC link")
            runCatching {
                entities.add(parseTuic(this))
            }.onFailure {
                Logs.d("TUIC link rejected (${it.javaClass.simpleName})")
            }
        } else if (startsWith("anytls://")) {
            Logs.d("Try parse AnyTLS link")
            runCatching {
                entities.add(parseAnytls(this))
            }.onFailure {
                Logs.d("AnyTLS link rejected (${it.javaClass.simpleName})")
            }
        }
    }

    for (link in links) {
        link.parseLink(entities)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine)
    }
//    var isBadLink = false
    if (entities.onEach { it.initializeDefaultValues() }.size == entitiesByLine.onEach { it.initializeDefaultValues() }.size) run test@{
        entities.forEachIndexed { index, bean ->
            val lineBean = entitiesByLine[index]
            if (bean == lineBean && bean.displayName() != lineBean.displayName()) {
//                isBadLink = true
                return@test
            }
        }
    }
    return if (entities.size > entitiesByLine.size) entities else entitiesByLine
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}
