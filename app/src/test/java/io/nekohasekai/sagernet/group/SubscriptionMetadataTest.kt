package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionMetadataTest {
    @Test
    fun `userinfo keeps only supported non-negative numeric values`() {
        assertEquals(
            "upload=12; download=34; total=100; expire=1893456000",
            SubscriptionMetadata.sanitizeUserInfo(
                "upload=12; download=34; total=100; expire=1893456000; title=unsafe; upload=-1",
            ),
        )
    }

    @Test
    fun `userinfo rejects overflow and control text`() {
        assertEquals(
            "total=1024",
            SubscriptionMetadata.sanitizeUserInfo(
                "upload=99999999999999999999; download=1\r\nInjected: true; total=1024",
            ),
        )
    }

    @Test
    fun `userinfo keeps one bounded value per field`() {
        assertEquals(
            "upload=1; total=3",
            SubscriptionMetadata.sanitizeUserInfo("upload=1; upload=2; total=3"),
        )
        assertEquals("", SubscriptionMetadata.sanitizeUserInfo("total=1;".repeat(600)))
    }

    @Test
    fun `extended filename is decoded and normalized`() {
        assertEquals(
            "我的 机场",
            SubscriptionMetadata.displayName(
                "attachment; filename*=UTF-8''%E6%88%91%E7%9A%84%20%20%E6%9C%BA%E5%9C%BA",
            ),
        )
    }

    @Test
    fun `regular filename is supported`() {
        assertEquals(
            "Neko subscription",
            SubscriptionMetadata.displayName("attachment; filename=\"Neko subscription\""),
        )
    }

    @Test
    fun `malformed or oversized filenames are ignored`() {
        assertNull(SubscriptionMetadata.displayName("attachment; filename*=UTF-8''bad%ZZname"))
        assertNull(SubscriptionMetadata.displayName("x".repeat(4097)))
    }

    @Test
    fun `display name is bounded by unicode code points`() {
        assertEquals(
            "猫".repeat(80),
            SubscriptionMetadata.displayName("attachment; filename=\"${"猫".repeat(120)}\""),
        )
    }
}
