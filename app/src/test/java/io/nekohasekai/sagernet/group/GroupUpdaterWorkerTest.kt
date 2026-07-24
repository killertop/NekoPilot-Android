package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking

class GroupUpdaterWorkerTest {
    @Test
    fun largeSubscriptionResolutionKeepsWorkerCountBounded() {
        assertEquals(0, GroupUpdater.resolveWorkerCount(0))
        assertEquals(1, GroupUpdater.resolveWorkerCount(1))
        assertEquals(5, GroupUpdater.resolveWorkerCount(5))
        assertEquals(5, GroupUpdater.resolveWorkerCount(10_000))
    }

    @Test
    fun blockingHostLookupIsInterruptedAtThePerHostDeadline() = runBlocking {
        var interrupted = false
        try {
            resolveHostWithTimeout(timeoutMs = 50) {
                try {
                    Thread.sleep(10_000)
                } catch (error: InterruptedException) {
                    interrupted = true
                    throw error
                }
                emptyList()
            }
            fail("expected the resolver deadline to be enforced")
        } catch (_: TimeoutCancellationException) {
            assertTrue(interrupted)
        }
    }
}
