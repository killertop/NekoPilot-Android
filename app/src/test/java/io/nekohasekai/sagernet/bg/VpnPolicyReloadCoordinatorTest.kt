package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VpnPolicyReloadCoordinatorTest {
    @Test
    fun rapidRequestsExecuteOnlyLatestReload() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val executed = CountDownLatch(1)
        val debouncer = DebouncedApplicationAction(scope, delayMillis = 40L) {
            calls.incrementAndGet()
            executed.countDown()
        }

        repeat(5) { debouncer.request() }

        assertTrue(executed.await(5, TimeUnit.SECONDS))
        Thread.sleep(80L)
        assertEquals(1, calls.get())
        debouncer.close()
        scope.cancel()
    }

    @Test
    fun closeCancelsPendingReload() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val debouncer = DebouncedApplicationAction(scope, delayMillis = 100L) {
            calls.incrementAndGet()
        }

        debouncer.request()
        debouncer.close()
        Thread.sleep(150L)

        assertEquals(0, calls.get())
        scope.cancel()
    }
}
