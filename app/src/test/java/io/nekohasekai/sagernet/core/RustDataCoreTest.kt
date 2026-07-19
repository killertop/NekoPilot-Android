package io.nekohasekai.sagernet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RustDataCoreTest {
    @Test
    fun matchesDuplicateNamesWithoutDiscardingEitherNode() {
        val plan = RustDataCore.planSubscriptionUpdate(
            incoming = listOf(
                RustDataCore.SubscriptionIncoming("same", listOf(2L)),
                RustDataCore.SubscriptionIncoming("same", listOf(1L)),
                RustDataCore.SubscriptionIncoming("new", emptyList()),
            ),
            existing = listOf(
                RustDataCore.SubscriptionExisting(1L, "same", 2L),
                RustDataCore.SubscriptionExisting(2L, "same", 1L),
                RustDataCore.SubscriptionExisting(3L, "old", 3L),
            ),
        )

        assertEquals(2L, plan.actions[0].existingId)
        assertEquals(RustDataCore.SubscriptionActionKind.UNCHANGED, plan.actions[0].action)
        assertEquals(1L, plan.actions[1].existingId)
        assertEquals(RustDataCore.SubscriptionActionKind.UNCHANGED, plan.actions[1].action)
        assertEquals(RustDataCore.SubscriptionActionKind.ADD, plan.actions[2].action)
        assertEquals(listOf(3L), plan.deletionIds)
    }

    @Test
    fun identifiesContentAndOrderChangesAndSelectionFallback() {
        val plan = RustDataCore.planSubscriptionUpdate(
            incoming = listOf(
                RustDataCore.SubscriptionIncoming("one", emptyList()),
                RustDataCore.SubscriptionIncoming("two", listOf(2L)),
            ),
            existing = listOf(
                RustDataCore.SubscriptionExisting(1L, "one", 1L),
                RustDataCore.SubscriptionExisting(2L, "two", 3L),
            ),
        )

        assertEquals(RustDataCore.SubscriptionActionKind.UPDATE, plan.actions[0].action)
        assertEquals(RustDataCore.SubscriptionActionKind.REORDER, plan.actions[1].action)
        assertTrue(RustDataCore.requiresSubscriptionSelectionFallback(false))
        assertFalse(RustDataCore.requiresSubscriptionSelectionFallback(true))
    }
}
