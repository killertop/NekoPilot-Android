package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionOptimizerTest {
    @Test
    fun uniqueNamesHandleNaturalSuffixCollisions() {
        assertEquals(
            listOf("Node", "Node (1)", "Node (1) (1)", "Node (2)"),
            makeUniqueNames(listOf("Node", "Node", "Node (1)", "Node")),
        )
    }

    @Test
    fun indexedDeduplicationPreservesFirstOccurrence() {
        val input = listOf("a:first", "b:second", "a:third", "a:fourth")
        val result = deduplicateIndexed(
            input,
            keyOf = { it.substringBefore(':') },
            nameOf = { it.substringAfter(':') },
        )
        assertEquals(listOf("a:first", "b:second"), result.unique)
        assertEquals(listOf("first (0)", "third (0)", "fourth (0)"), result.duplicateLabels)
    }

    @Test
    fun largeDuplicateInputRemainsDeterministic() {
        val result = deduplicateIndexed(
            List(5_000) { "same" },
            keyOf = { it },
            nameOf = { it },
        )
        assertEquals(listOf("same"), result.unique)
        assertEquals(5_000, result.duplicateLabels.size)
    }
}
