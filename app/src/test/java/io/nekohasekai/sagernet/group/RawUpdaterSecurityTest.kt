package io.nekohasekai.sagernet.group

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawUpdaterSecurityTest {

    @Test
    fun subscriptionUrlRequiresHttpsWithoutEmbeddedCredentials() {
        assertEquals("subscription.test", validateSubscriptionUrl("https://subscription.test/list").host)

        assertTrue(runCatching { validateSubscriptionUrl("http://subscription.test/list") }.isFailure)
        assertTrue(runCatching { validateSubscriptionUrl("https://user:pass@subscription.test/list") }.isFailure)
    }

    @Test
    fun privateAndReservedAddressesAreRejected() {
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "192.168.1.1",
            "198.18.0.1",
            "::1",
            "fd00::1",
            "fe80::1",
        ).forEach { host ->
            assertTrue(host, isNonPublicAddressLiteral(host))
        }
        assertFalse(isNonPublicAddress(InetAddress.getByName("1.1.1.1")))
    }
}
