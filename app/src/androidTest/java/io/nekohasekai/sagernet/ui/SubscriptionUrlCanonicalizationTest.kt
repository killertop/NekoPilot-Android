package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUrlCanonicalizationTest {
    @Test
    fun directHttpsViewIntentIsNormalizedAsSubscription() {
        val source = "https://provider.example/sub?token=a%2Fb"
        val normalized = NodeImportCoordinator.subscriptionImportUri(source)!!

        assertEquals("sn", normalized.scheme)
        assertEquals("subscription", normalized.host)
        assertEquals(source, normalized.getQueryParameter("url"))
        assertNull(NodeImportCoordinator.subscriptionImportUri("http://provider.example/sub"))
        assertNull(NodeImportCoordinator.subscriptionImportUri("https://user:secret@provider.example/sub"))
        assertNull(NodeImportCoordinator.subscriptionImportUri("clash://untrusted-host?url=https://provider.example/sub"))
    }

    @Test
    fun canonicalizationPreservesProviderIdentity() {
        assertEquals(
            canonicalSubscriptionUrlKey("https://host.example/path"),
            canonicalSubscriptionUrlKey("HTTPS://HOST.EXAMPLE:443/path#temporary"),
        )
        assertEquals(
            canonicalSubscriptionUrlKey("https://例子.测试/sub?token=signed"),
            canonicalSubscriptionUrlKey("https://xn--fsqu00a.xn--0zwm56d/sub?token=signed"),
        )
        assertNull(canonicalSubscriptionUrlKey(" HTTP://HOST.EXAMPLE...:00080#temporary "))
        assertEquals(
            canonicalSubscriptionUrlKey("https://host.example/path"),
            canonicalSubscriptionUrlKey("https://host.example:0443/path"),
        )
        assertTrue(
            sameSubscriptionUrl(
                "HTTPS://[2001:DB8::1]:443/sub?token=A%2Fb#ignored",
                "https://[2001:db8::1]/sub?token=A%2Fb",
            ),
        )
        assertNotEquals(
            canonicalSubscriptionUrlKey("https://host.example/a?token=A%2Fb"),
            canonicalSubscriptionUrlKey("https://host.example/a?token=A/b"),
        )
        assertFalse(sameSubscriptionUrl("https://alice@host.example/a", "https://bob@host.example/a"))
        assertNull(canonicalSubscriptionUrlKey("https://alice:secret@host.example/a"))
        assertFalse(sameSubscriptionUrl("https://host.example/path?", "https://host.example/path"))
    }

    @Test
    fun invalidUrlsUseTrimmedFallbackWithoutEmptyKeyCollision() {
        assertNull(canonicalSubscriptionUrlKey("file:///tmp/subscription"))
        assertNull(canonicalSubscriptionUrlKey("not a url"))
        assertFalse(sameSubscriptionUrl("first invalid url", "second invalid url"))
        assertTrue(sameSubscriptionUrl(" same invalid url ", "same invalid url"))
    }
}
