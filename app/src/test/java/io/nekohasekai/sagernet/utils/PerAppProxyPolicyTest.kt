package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppProxyPolicyTest {

    @Test
    fun coreSystemUidsAreNotIndividuallySelectable() {
        assertFalse(isPerAppSelectableUid(0))
        assertFalse(isPerAppSelectableUid(1_000))
        assertFalse(isPerAppSelectableUid(9_999))
        assertTrue(isPerAppSelectableUid(10_000))
        assertTrue(isPerAppSelectableUid(10_383))
    }

    @Test
    fun removesInstalledCoreComponentsButPreservesAppsAndMissingPackages() {
        val result = sanitizePerAppPackages(
            selectedPackages = listOf(
                "android",
                "com.xiaomi.aiasst.service",
                "com.openai.chatgpt",
                "com.example.not.installed",
            ),
            installedUids = mapOf(
                "android" to 1_000,
                "com.xiaomi.aiasst.service" to 1_000,
                "com.openai.chatgpt" to 10_383,
            ),
        )

        assertEquals(
            linkedSetOf("com.openai.chatgpt", "com.example.not.installed"),
            result,
        )
    }

    @Test
    fun normalizesBlankBomAndDuplicateEntries() {
        assertEquals(
            linkedSetOf("com.openai.chatgpt"),
            sanitizePerAppPackages(
                selectedPackages = listOf("", "\uFEFFcom.openai.chatgpt", "com.openai.chatgpt"),
                installedUids = mapOf("com.openai.chatgpt" to 10_383),
            ),
        )
    }
}
