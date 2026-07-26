package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NativeInterfaceMonitorGateTest {
    @Test
    fun closeWaitsForInFlightUpdateAndRejectsLaterCalls() {
        val gate = NativeInterfaceMonitorGate<Any>()
        val listener = Any()
        val updateEntered = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val firstPublished = AtomicBoolean(false)
        gate.start(listener)

        Thread {
            firstPublished.set(
                gate.publish {
                    updateEntered.countDown()
                    assertTrue(releaseUpdate.await(5, TimeUnit.SECONDS))
                },
            )
        }.start()
        assertTrue(updateEntered.await(5, TimeUnit.SECONDS))

        Thread {
            gate.close(listener)
            closeFinished.countDown()
        }.start()

        assertFalse(closeFinished.await(50, TimeUnit.MILLISECONDS))
        releaseUpdate.countDown()
        assertTrue(closeFinished.await(5, TimeUnit.SECONDS))
        assertTrue(firstPublished.get())
        assertFalse(gate.publish { error("closed listener was invoked") })
    }

    @Test
    fun staleCloseCannotRemoveReplacementListener() {
        val gate = NativeInterfaceMonitorGate<Any>()
        val oldListener = Any()
        val replacement = Any()
        val published = AtomicReference<Any?>()
        gate.start(oldListener)
        gate.start(replacement)

        gate.close(oldListener)

        assertTrue(gate.publish(published::set))
        assertSame(replacement, published.get())
    }
}
