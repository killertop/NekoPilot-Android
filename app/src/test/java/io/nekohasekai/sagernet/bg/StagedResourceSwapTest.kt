package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class StagedResourceSwapTest {
    @Test
    fun terminalTeardownTakesOnlyPendingCandidateAndLeavesActiveResourceUntouched() {
        val active = Any()
        val published = AtomicReference(active)
        val candidate = Any()
        val swap = StagedResourceSwap<Any>()

        swap.begin()
        swap.stage(candidate)

        assertSame(candidate, swap.takePending())
        assertSame(active, published.get())
        assertNull(swap.takePending())
    }

    @Test
    fun commitPublishesCandidateOnlyAfterExplicitCommit() {
        val candidate = Any()
        val swap = StagedResourceSwap<Any>()

        swap.begin()
        swap.stage(candidate)

        assertSame(candidate, swap.commit())
        assertThrows(IllegalStateException::class.java) { swap.commit() }
    }

    @Test
    fun concurrentReloadCannotReplacePendingCandidate() {
        val swap = StagedResourceSwap<Any>()
        swap.begin()
        swap.stage(Any())

        assertThrows(IllegalStateException::class.java) { swap.begin() }
        assertThrows(IllegalStateException::class.java) { swap.stage(Any()) }
        assertEquals(true, swap.isInProgress())
    }
}
