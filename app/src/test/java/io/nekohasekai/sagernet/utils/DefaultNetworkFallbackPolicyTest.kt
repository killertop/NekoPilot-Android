package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkFallbackPolicyTest {
    @Test
    fun fallbackStatePublishesOnlyRealNetworkTransitions() {
        val state = FallbackNetworkState<String>()

        assertFalse(state.update(null))
        assertTrue(state.update("wifi"))
        assertFalse(state.update("wifi"))
        assertTrue(state.update("cellular"))
        assertTrue(state.update(null))
        assertFalse(state.update(null))
    }

    @Test
    fun registrationRetryUsesBoundedExponentialBackoff() {
        assertEquals(1_000L, registrationRetryDelayMillis(0))
        assertEquals(2_000L, registrationRetryDelayMillis(1))
        assertEquals(4_000L, registrationRetryDelayMillis(2))
        assertEquals(16_000L, registrationRetryDelayMillis(4))
        assertEquals(30_000L, registrationRetryDelayMillis(5))
        assertEquals(30_000L, registrationRetryDelayMillis(30))
    }

    @Test
    fun uncertainRegistrationFailureAlwaysAttemptsCompensatingUnregister() {
        val callback = Any()
        var accepted = false
        var compensated: Any? = null

        val result = registerWithCompensation(
            callback = callback,
            register = {
                accepted = true
                throw IllegalStateException("Binder reply failed")
            },
            unregister = {
                if (accepted) compensated = it
            },
        )

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(compensated === callback)
    }

    @Test
    fun successfulRegistrationDoesNotUnregister() {
        var unregisterCount = 0

        val result = registerWithCompensation(
            callback = Any(),
            register = {},
            unregister = { unregisterCount++ },
        )

        assertTrue(result.isSuccess)
        assertEquals(0, unregisterCount)
    }
}
