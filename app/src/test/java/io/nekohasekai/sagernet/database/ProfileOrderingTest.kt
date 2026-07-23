package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileOrderingTest {
    @Test
    fun persistedOrderIsOneBasedAndStable() {
        assertEquals(
            listOf(
                ProxyEntity.OrderUpdate(9L, 1L),
                ProxyEntity.OrderUpdate(4L, 2L),
                ProxyEntity.OrderUpdate(7L, 3L),
            ),
            profileOrderUpdates(listOf(9L, 4L, 7L)),
        )
    }
}
