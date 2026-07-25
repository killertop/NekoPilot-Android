package io.nekohasekai.sagernet.fmt.hysteria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaServerPortsTest {
    @Test
    fun parsesSinglePortsAndCanonicalizesPortLists() {
        assertEquals(
            HysteriaServerPorts.Single(443),
            parseHysteriaServerPorts("443"),
        )
        assertEquals(
            HysteriaServerPorts.Ranges(listOf("2000:2002", "3000:3000", "4000:4001")),
            parseHysteriaServerPorts("2000-2002, 3000, 4000:4001"),
        )
    }

    @Test
    fun rejectsMalformedOrOutOfRangePortLists() {
        listOf("", "443,", "0", "65536", "2002-2000", "2000--2002", "x").forEach { value ->
            val failure = runCatching { parseHysteriaServerPorts(value) }.exceptionOrNull()
            assertTrue("expected $value to be rejected", failure is IllegalArgumentException || failure is IllegalStateException)
        }
    }
}
