package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun mapsSwitchToSupportedLanguageTag() {
        assertEquals("zh-CN", languageTagForChineseInterface(true))
        assertEquals("en", languageTagForChineseInterface(false))
    }

    @Test
    fun recognizesChineseSystemLanguage() {
        assertTrue(isChineseLanguage("zh"))
        assertTrue(isChineseLanguage("ZH"))
        assertFalse(isChineseLanguage("en"))
        assertFalse(isChineseLanguage(null))
    }
}
