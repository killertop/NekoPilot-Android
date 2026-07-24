package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunDescriptorDuplicationTest {
    @Test
    fun secondDuplicationFailureClosesTheFirstDescriptor() {
        val first = TrackedResource()
        var calls = 0

        val failure = runCatching {
            duplicatePairWithRollback {
                calls++
                if (calls == 1) first else error("second duplication failed")
            }
        }

        assertTrue(failure.isFailure)
        assertTrue(first.closed)
    }

    @Test
    fun successfulDuplicationLeavesBothDescriptorsOwnedByTheCaller() {
        val resources = listOf(TrackedResource(), TrackedResource())
        var calls = 0

        val pair = duplicatePairWithRollback { resources[calls++] }

        assertFalse(pair.first.closed)
        assertFalse(pair.second.closed)
    }

    private class TrackedResource : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }
}
