package moe.matsuri.nb4a

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionFailureCategoryTest {

    @Test
    fun categorizesCommonConnectionFailures() {
        assertEquals(
            ConnectionFailureCategory.TIMEOUT,
            connectionFailureCategory("context deadline exceeded"),
        )
        assertEquals(
            ConnectionFailureCategory.RESET,
            connectionFailureCategory("use of closed network connection"),
        )
        assertEquals(
            ConnectionFailureCategory.RESET,
            connectionFailureCategory("read: connection reset by peer"),
        )
        assertEquals(
            ConnectionFailureCategory.OTHER,
            connectionFailureCategory("certificate is not trusted"),
        )
    }
}
