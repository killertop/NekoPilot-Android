package moe.matsuri.nb4a.proxy.anytls

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

fun parseAnytls(url: String): AnyTLSBean {
    val link = url.replaceFirst(Regex("(?i)^anytls://"), "https://").toHttpUrlOrNull()
        ?: error("Invalid AnyTLS link")
    require(link.queryParameterNames.all { it in ANYTLS_URI_PARAMETERS }) {
        "Unsupported AnyTLS link parameter"
    }
    link.queryParameterNames.forEach { parameter ->
        require(link.queryParameterValues(parameter).size == 1) {
            "Duplicate AnyTLS link parameter: $parameter"
        }
    }
    val password = link.username
    require(password.isNotBlank()) { "AnyTLS password is required" }
    return AnyTLSBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment.orEmpty()
        this.password = password
        sni = link.queryParameter("sni") ?: ""
        allowInsecure = parseAnytlsBooleanParameter(link.queryParameter("insecure"))
        utlsFingerprint = link.queryParameter("fp") ?: ""
        initializeDefaultValues()
    }
}

private fun parseAnytlsBooleanParameter(value: String?): Boolean = when (value?.lowercase(Locale.ROOT)) {
    null, "", "0", "false" -> false
    "1", "true" -> true
    else -> error("Invalid AnyTLS insecure parameter")
}

private val ANYTLS_URI_PARAMETERS = setOf("sni", "insecure", "fp")
