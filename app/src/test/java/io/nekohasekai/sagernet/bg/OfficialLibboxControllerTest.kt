package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OfficialLibboxControllerTest {
    @Test
    fun breakBeforeMakeFailureLeavesControllerReusableForLastKnownGoodRecovery() {
        val commandServer = RecordingCommandServer()
        val controller = OfficialLibboxController(commandServer)

        controller.startOrReload("last-known-good")
        assertEquals("last-known-good", commandServer.runningConfig.get())
        commandServer.nextReloadFailure.set(IllegalArgumentException("bad candidate"))

        val failure = runCatching {
            controller.startOrReload("candidate")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        // Model upstream StartOrReloadService: it closes the old instance before trying the new
        // one, so caller recovery must recreate the LKG service on the same command server.
        assertNull(commandServer.runningConfig.get())
        assertEquals(0, commandServer.closeServiceCount.get())
        assertEquals(0, commandServer.closeCount.get())
        controller.pause()
        assertEquals(1, commandServer.pauseCount.get())

        controller.startOrReload("last-known-good")
        assertEquals("last-known-good", commandServer.runningConfig.get())
        assertEquals(listOf("last-known-good", "candidate", "last-known-good"), commandServer.configs)
        controller.close()
        assertEquals(1, commandServer.closeServiceCount.get())
        assertEquals(1, commandServer.closeCount.get())
    }

    @Test
    fun failedInitialStartClosesPartialNativeResources() {
        val commandServer = RecordingCommandServer()
        val controller = OfficialLibboxController(commandServer)
        commandServer.nextReloadFailure.set(IllegalStateException("cannot start"))

        val failure = runCatching { controller.startOrReload("{}") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, commandServer.closeServiceCount.get())
        assertEquals(1, commandServer.closeCount.get())
    }

    @Test
    fun concurrentReloadsAreSerialized() {
        val commandServer = SerializingCommandServer()
        val controller = OfficialLibboxController(commandServer)
        controller.startOrReload("initial")

        val firstFinished = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        Thread {
            controller.startOrReload("first")
            firstFinished.countDown()
        }.start()
        assertTrue(commandServer.firstReloadEntered.await(5, TimeUnit.SECONDS))
        Thread {
            controller.startOrReload("second")
            secondFinished.countDown()
        }.start()

        assertFalse(commandServer.secondReloadEntered.await(100, TimeUnit.MILLISECONDS))
        commandServer.allowFirstReload.countDown()
        assertTrue(firstFinished.await(5, TimeUnit.SECONDS))
        assertTrue(commandServer.secondReloadEntered.await(5, TimeUnit.SECONDS))
        assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
        assertEquals(1, commandServer.maxConcurrentCalls.get())
        controller.close()
    }

    @Test
    fun requestCloseReachesNativeWhileStartOrReloadIsBlocked() {
        val commandServer = BlockingCommandServer()
        val controller = OfficialLibboxController(
            commandServer = commandServer,
            closeDispatcher = { block ->
                Thread(block, "test-native-close").apply {
                    isDaemon = true
                    start()
                }
            },
        )
        val startupFailure = AtomicReference<Throwable?>()
        val startupFinished = CountDownLatch(1)
        val startupThread = Thread {
            try {
                controller.startOrReload("{}")
            } catch (error: Throwable) {
                startupFailure.set(error)
            } finally {
                startupFinished.countDown()
            }
        }

        startupThread.start()
        assertTrue(commandServer.startOrReloadEntered.await(5, TimeUnit.SECONDS))

        controller.requestClose()

        // The fake native start returns only after close() is invoked. Reaching this latch proves
        // requestClose did not wait for startOrReload's operation monitor.
        assertTrue(commandServer.closeCalled.await(5, TimeUnit.SECONDS))
        assertTrue(startupFinished.await(5, TimeUnit.SECONDS))
        assertTrue(startupFailure.get() is CancellationException)
        // The service had not returned from its first start, so only the command server itself
        // owns native resources at this point.
        assertEquals(0, commandServer.closeServiceCount.get())
        assertEquals(1, commandServer.closeCount.get())
    }

    @Test
    fun repeatedCloseRequestsSendOneNativeSignal() {
        val commandServer = BlockingCommandServer(blockStartup = false)
        val queuedClose = AtomicReference<(() -> Unit)?>()
        val controller = OfficialLibboxController(
            commandServer = commandServer,
            closeDispatcher = { block ->
                assertTrue(queuedClose.compareAndSet(null, block))
            },
        )
        controller.startOrReload("{}")

        controller.requestClose()
        controller.requestClose()

        assertFalse(commandServer.closeCalled.await(50, TimeUnit.MILLISECONDS))
        queuedClose.getAndSet(null)!!.invoke()
        assertTrue(commandServer.closeCalled.await(5, TimeUnit.SECONDS))
        assertEquals(1, commandServer.closeServiceCount.get())
        assertEquals(1, commandServer.closeCount.get())
    }

    private class BlockingCommandServer(
        private val blockStartup: Boolean = true,
    ) : LibboxCommandServer {
        val startOrReloadEntered = CountDownLatch(1)
        val closeCalled = CountDownLatch(1)
        val closeServiceCount = AtomicInteger()
        val closeCount = AtomicInteger()

        override fun start() = Unit

        override fun startOrReloadService(
            config: String,
            includePackages: Collection<String>,
            excludePackages: Collection<String>,
        ) {
            startOrReloadEntered.countDown()
            if (blockStartup) {
                check(closeCalled.await(5, TimeUnit.SECONDS)) {
                    "Native close signal did not arrive while startup was blocked"
                }
            }
        }

        override fun closeService() {
            closeServiceCount.incrementAndGet()
        }

        override fun close() {
            closeCount.incrementAndGet()
            closeCalled.countDown()
        }

        override fun pause() = Unit
        override fun wake() = Unit
        override fun resetNetwork() = Unit
    }

    private class RecordingCommandServer : LibboxCommandServer {
        val configs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val nextReloadFailure = AtomicReference<Throwable?>()
        val runningConfig = AtomicReference<String?>()
        val closeServiceCount = AtomicInteger()
        val closeCount = AtomicInteger()
        val pauseCount = AtomicInteger()

        override fun start() = Unit

        override fun startOrReloadService(
            config: String,
            includePackages: Collection<String>,
            excludePackages: Collection<String>,
        ) {
            runningConfig.set(null)
            configs += config
            nextReloadFailure.getAndSet(null)?.let { throw it }
            runningConfig.set(config)
        }

        override fun closeService() {
            closeServiceCount.incrementAndGet()
        }

        override fun close() {
            closeCount.incrementAndGet()
        }

        override fun pause() {
            pauseCount.incrementAndGet()
        }

        override fun wake() = Unit
        override fun resetNetwork() = Unit
    }

    private class SerializingCommandServer : LibboxCommandServer {
        val firstReloadEntered = CountDownLatch(1)
        val secondReloadEntered = CountDownLatch(1)
        val allowFirstReload = CountDownLatch(1)
        val maxConcurrentCalls = AtomicInteger()
        private val activeCalls = AtomicInteger()

        override fun start() = Unit

        override fun startOrReloadService(
            config: String,
            includePackages: Collection<String>,
            excludePackages: Collection<String>,
        ) {
            val active = activeCalls.incrementAndGet()
            maxConcurrentCalls.updateAndGet { previous -> maxOf(previous, active) }
            try {
                when (config) {
                    "first" -> {
                        firstReloadEntered.countDown()
                        assertTrue(allowFirstReload.await(5, TimeUnit.SECONDS))
                    }
                    "second" -> secondReloadEntered.countDown()
                }
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        override fun closeService() = Unit
        override fun close() = Unit
        override fun pause() = Unit
        override fun wake() = Unit
        override fun resetNetwork() = Unit
    }
}
