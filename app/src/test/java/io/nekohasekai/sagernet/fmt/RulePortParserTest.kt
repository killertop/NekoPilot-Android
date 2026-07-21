package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Test

class RulePortParserTest {
    @Test
    fun preservesValidOrderedPortsAndRangesWhileIgnoringInvalidInput() {
        assertEquals(
            ParsedRulePorts(
                ports = listOf(53, 443),
                ranges = listOf("1000:2000"),
            ),
            parseRulePorts("53, 443, 1000:2000, 0, 70000, invalid, 2000:1000, 443"),
        )
    }
}
