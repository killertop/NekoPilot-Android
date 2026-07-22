package io.nekohasekai.sagernet.ui

internal fun languageTagForChineseInterface(enabled: Boolean): String =
    if (enabled) "zh-CN" else "en"

internal fun isChineseLanguage(language: String?): Boolean =
    language.equals("zh", ignoreCase = true)
