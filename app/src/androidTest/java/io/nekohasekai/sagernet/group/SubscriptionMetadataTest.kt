package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionMetadataTest {
    @Test
    fun userInfoUsesGoValidationAndCanonicalization() {
        val sanitized = SubscriptionMetadata.sanitizeUserInfo(
            "upload=12; download=34; total=100; expire=1893456000; title=unsafe; upload=-1",
        )
        assertEquals("upload=12; download=34; total=100; expire=1893456000", sanitized)

        val parsed = SubscriptionMetadata.parseUserInfo(sanitized)
        assertEquals(12L, parsed.upload)
        assertEquals(34L, parsed.download)
        assertEquals(100L, parsed.total)
        assertEquals(1893456000L, parsed.expire)

        val rejected = SubscriptionMetadata.parseUserInfo(
            "upload=99999999999999999999; download=1\r\nInjected: true; total=1024",
        )
        assertNull(rejected.upload)
        assertNull(rejected.download)
        assertEquals(1024L, rejected.total)
        assertEquals("", SubscriptionMetadata.sanitizeUserInfo("total=1;".repeat(600)))
    }

    @Test
    fun displayNameUsesGoUnicodeAndBoundsHandling() {
        assertEquals(
            "我的 机场",
            SubscriptionMetadata.displayName(
                "attachment; filename*=UTF-8''%E6%88%91%E7%9A%84%20%20%E6%9C%BA%E5%9C%BA",
            ),
        )
        assertEquals(
            "猫".repeat(80),
            SubscriptionMetadata.displayName("attachment; filename=\"${"猫".repeat(120)}\""),
        )
        assertNull(SubscriptionMetadata.displayName("attachment; filename*=UTF-8''bad%ZZname"))
        assertEquals(
            "fallback",
            SubscriptionMetadata.displayName(
                "attachment; filename*=UTF-8''bad%ZZname; filename=fallback",
            ),
        )
        assertNull(SubscriptionMetadata.displayName("x".repeat(4097)))
    }
}
