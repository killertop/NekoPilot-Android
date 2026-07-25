package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun nativeCallbackLeaseClosesDescriptorsOnlyAfterTheNativeCallReturns() {
        val lease = NativeCallbackDescriptorLease<TrackedResource>()
        val descriptor = TrackedResource()

        lease.duringNativeCall {
            lease.track(descriptor)
            assertFalse(descriptor.closed)
        }

        assertTrue(descriptor.closed)
    }

    @Test
    fun nativeCallbackLeaseClosesDescriptorsWhenNativeStartFails() {
        val lease = NativeCallbackDescriptorLease<TrackedResource>()
        val descriptor = TrackedResource()

        val failure = runCatching {
            lease.duringNativeCall {
                lease.track(descriptor)
                error("native start failed")
            }
        }

        assertTrue(failure.isFailure)
        assertTrue(descriptor.closed)
    }

    @Test
    fun nativeCallbackLeaseDoesNotAccumulateAcrossRepeatedReloads() {
        val lease = NativeCallbackDescriptorLease<TrackedResource>()
        val descriptors = ArrayList<TrackedResource>()

        repeat(100) {
            val descriptor = TrackedResource()
            descriptors += descriptor
            lease.duringNativeCall { lease.track(descriptor) }
        }

        assertEquals(100, descriptors.count { it.closed })
    }

    private class TrackedResource : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }
}
