package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUrlCanonicalizationTest {

    @Test
    fun canonicalizesHostSchemeDefaultPortEmptyPathAndFragment() {
        assertEquals(
            canonicalSubscriptionUrlKey("https://host.example/path"),
            canonicalSubscriptionUrlKey("HTTPS://HOST.EXAMPLE:443/path#temporary"),
        )
        assertEquals(
            canonicalSubscriptionUrlKey("http://host.example"),
            canonicalSubscriptionUrlKey("HTTP://HOST.EXAMPLE:80/"),
        )
    }

    @Test
    fun preservesSignedPathQueryAndCredentials() {
        assertNotEquals(
            canonicalSubscriptionUrlKey("https://host.example/a?token=A%2Fb"),
            canonicalSubscriptionUrlKey("https://host.example/a?token=A/b"),
        )
        assertNotEquals(
            canonicalSubscriptionUrlKey("https://alice@host.example/a"),
            canonicalSubscriptionUrlKey("https://bob@host.example/a"),
        )
    }

    @Test
    fun rejectsUnsupportedOrHostlessUrls() {
        assertNull(canonicalSubscriptionUrlKey("file:///tmp/subscription"))
        assertNull(canonicalSubscriptionUrlKey("not a url"))
    }

    @Test
    fun canonicalizesUnicodeHostsWithoutColliding() {
        assertEquals(
            canonicalSubscriptionUrlKey("https://例子.测试/sub?token=signed"),
            canonicalSubscriptionUrlKey("https://xn--fsqu00a.xn--0zwm56d/sub?token=signed"),
        )
        assertFalse(
            sameSubscriptionUrl(
                "https://例子.测试/sub",
                "https://例子.中国/sub",
            ),
        )
    }

    @Test
    fun canonicalizesIpv6AndNeverTreatsTwoInvalidUrlsAsEqualByNullKey() {
        assertTrue(
            sameSubscriptionUrl(
                "HTTPS://[2001:DB8::1]:443/sub?token=A%2Fb#ignored",
                "https://[2001:db8::1]/sub?token=A%2Fb",
            ),
        )
        assertFalse(sameSubscriptionUrl("first invalid url", "second invalid url"))
        assertTrue(sameSubscriptionUrl(" same invalid url ", "same invalid url"))
    }
}
