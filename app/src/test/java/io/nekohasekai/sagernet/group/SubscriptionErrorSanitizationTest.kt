package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionErrorSanitizationTest {

    @Test
    fun removesKnownSubscriptionCredentialsPathAndQuery() {
        val link = "https://user:password@example.com/private/account/token?key=top-secret"

        val result = sanitizeSubscriptionError("GET $link: timeout", link)

        assertEquals("GET https://example.com/…: timeout", result)
        assertFalse(result.contains("password"))
        assertFalse(result.contains("private"))
        assertFalse(result.contains("top-secret"))
    }

    @Test
    fun removesRedirectUrlThatDoesNotMatchOriginalLink() {
        val result = sanitizeSubscriptionError(
            "redirected to https://cdn.example.net/download/opaque-token?signature=secret and failed",
            "https://example.com/subscription",
        )

        assertEquals("redirected to https://cdn.example.net/… and failed", result)
        assertFalse(result.contains("opaque-token"))
        assertFalse(result.contains("secret"))
    }

    @Test
    fun keepsUsefulNonUrlNetworkReason() {
        val result = sanitizeSubscriptionError("connection reset by peer", null)

        assertTrue(result.contains("connection reset by peer"))
    }

    @Test
    fun mapsTechnicalFailuresToShortUserFacingCategories() {
        assertEquals(
            R.string.subscription_update_timeout_error,
            subscriptionFailureMessageRes(SocketTimeoutException("read timed out")),
        )
        assertEquals(
            R.string.subscription_update_format_error,
            subscriptionFailureMessageRes(IllegalArgumentException("unsupported profile document")),
        )
        assertEquals(
            R.string.subscription_update_network_error,
            subscriptionFailureMessageRes(IllegalStateException("malformed HTTP response")),
        )
    }
}
