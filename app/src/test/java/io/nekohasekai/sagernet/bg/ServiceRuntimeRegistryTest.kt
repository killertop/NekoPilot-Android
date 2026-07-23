package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ServiceRuntimeRegistryTest {
    @Test
    fun staleServiceCannotClearReplacement() {
        val slot = OwnedRuntimeSlot<Any>()
        val oldService = Any()
        val replacement = Any()

        slot.register(oldService)
        slot.register(replacement)
        slot.unregister(oldService)

        assertSame(replacement, slot.value)
    }

    @Test
    fun currentServiceCanClearItself() {
        val slot = OwnedRuntimeSlot<Any>()
        val service = Any()

        slot.register(service)
        slot.unregister(service)

        assertNull(slot.value)
    }
}
