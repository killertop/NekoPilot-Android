package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ServiceLifecycleTest {
    @Test
    fun closeCancelsServiceOwnedChildren() {
        val lifecycle = ServiceLifecycle()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        lifecycle.scope.launch {
            try {
                started.countDown()
                awaitCancellation()
            } finally {
                cancelled.countDown()
            }
        }

        assertTrue(started.await(5, TimeUnit.SECONDS))
        lifecycle.close()

        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(lifecycle.destroyed)
    }

    @Test
    fun lateNativeResultIsClosedInsteadOfPublished() {
        val lifecycle = ServiceLifecycle()
        val published = AtomicReference<FakeResource?>()
        val localResource = FakeResource()

        lifecycle.close()
        val accepted = lifecycle.commitIfAlive { published.set(localResource) }
        if (!accepted) localResource.close()

        assertFalse(accepted)
        assertNull(published.get())
        assertEquals(1, localResource.closeCount.get())
    }

    @Test
    fun blockingNativeResultReturningAfterCloseCannotBePublished() {
        val lifecycle = ServiceLifecycle()
        val nativeStarted = CountDownLatch(1)
        val allowNativeReturn = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val published = AtomicReference<FakeResource?>()
        val localResource = FakeResource()

        lifecycle.scope.launch {
            nativeStarted.countDown()
            allowNativeReturn.await()
            val accepted = lifecycle.commitIfAlive { published.set(localResource) }
            if (!accepted) localResource.close()
            finished.countDown()
        }

        assertTrue(nativeStarted.await(5, TimeUnit.SECONDS))
        lifecycle.close()
        allowNativeReturn.countDown()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertNull(published.get())
        assertEquals(1, localResource.closeCount.get())
    }

    @Test
    fun resourcePublishedBeforeCloseRemainsVisibleToSynchronousCleanup() {
        val lifecycle = ServiceLifecycle()
        val published = AtomicReference<FakeResource?>()
        val resource = FakeResource()

        assertTrue(lifecycle.commitIfAlive { published.set(resource) })
        lifecycle.close()
        published.getAndSet(null)?.close()

        assertEquals(1, resource.closeCount.get())
        assertNull(published.get())
    }

    @Test
    fun closeCannotCleanUpBetweenConnectedCommitAndLateInitialization() {
        val lifecycle = ServiceLifecycle()
        val commitEntered = CountDownLatch(1)
        val allowLateInitialization = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val wakeLockHeld = AtomicReference(false)

        val connector = Thread {
            assertTrue(lifecycle.commitIfAlive {
                commitEntered.countDown()
                assertTrue(allowLateInitialization.await(5, TimeUnit.SECONDS))
                wakeLockHeld.set(true)
            })
        }
        val destroyer = Thread {
            closeStarted.countDown()
            lifecycle.close()
            wakeLockHeld.set(false)
            closeFinished.countDown()
        }

        connector.start()
        assertTrue(commitEntered.await(5, TimeUnit.SECONDS))
        destroyer.start()
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))

        allowLateInitialization.countDown()
        connector.join(5_000)
        assertFalse(connector.isAlive)
        assertTrue(closeFinished.await(5, TimeUnit.SECONDS))
        assertFalse(wakeLockHeld.get())
    }

    private class FakeResource : AutoCloseable {
        val closeCount = AtomicInteger()
        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}
