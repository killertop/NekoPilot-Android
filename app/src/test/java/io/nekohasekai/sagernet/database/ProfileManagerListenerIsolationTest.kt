package io.nekohasekai.sagernet.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProfileManagerListenerIsolationTest {

    @Test
    fun failedListenerIsLoggedByCategoryAndDoesNotStopLaterListeners() = runBlocking {
        val visited = mutableListOf<String>()
        val logs = mutableListOf<String>()

        dispatchListenerSnapshot(
            listeners = listOf("first", "broken", "last"),
            category = "Profile",
            logger = logs::add,
        ) {
            visited += this
            if (this == "broken") error("sensitive server detail")
        }

        assertEquals(listOf("first", "broken", "last"), visited)
        assertEquals(listOf("Profile listener failed (IllegalStateException)"), logs)
    }

    @Test
    fun cancellationIsRethrownAndStopsDispatch() {
        val visited = mutableListOf<String>()
        val logs = mutableListOf<String>()

        try {
            runBlocking {
                dispatchListenerSnapshot(
                    listeners = listOf("first", "cancelled", "last"),
                    category = "Rule",
                    logger = logs::add,
                ) {
                    visited += this
                    if (this == "cancelled") throw CancellationException("cancel")
                }
            }
            fail("CancellationException should be rethrown")
        } catch (_: CancellationException) {
            // Expected: coroutine cancellation is control flow, not a listener failure.
        }

        assertEquals(listOf("first", "cancelled"), visited)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun loggerFailureCannotTurnListenerIsolationIntoOperationFailure() = runBlocking {
        val visited = mutableListOf<String>()

        dispatchListenerSnapshot(
            listeners = listOf("broken", "last"),
            category = "Profile",
            logger = { error("logger unavailable") },
        ) {
            visited += this
            if (this == "broken") error("listener unavailable")
        }

        assertEquals(listOf("broken", "last"), visited)
    }
}
