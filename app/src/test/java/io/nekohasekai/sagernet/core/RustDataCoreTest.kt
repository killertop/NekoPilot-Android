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
                RustDataCore.SubscriptionIncoming("same", "second"),
                RustDataCore.SubscriptionIncoming("same", "first"),
                RustDataCore.SubscriptionIncoming("new", "new"),
            ),
            existing = listOf(
                RustDataCore.SubscriptionExisting(1L, "same", 2L, "first"),
                RustDataCore.SubscriptionExisting(2L, "same", 1L, "second"),
                RustDataCore.SubscriptionExisting(3L, "old", 3L, "old"),
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
    fun preservesDuplicateNodeIdentityWhenSubscriptionOrderChanges() {
        val plan = RustDataCore.planSubscriptionUpdate(
            incoming = listOf(
                RustDataCore.SubscriptionIncoming("same", "second"),
                RustDataCore.SubscriptionIncoming("same", "first"),
            ),
            existing = listOf(
                RustDataCore.SubscriptionExisting(1L, "same", 1L, "first"),
                RustDataCore.SubscriptionExisting(2L, "same", 2L, "second"),
            ),
        )

        assertEquals(2L, plan.actions[0].existingId)
        assertEquals(RustDataCore.SubscriptionActionKind.REORDER, plan.actions[0].action)
        assertEquals(1L, plan.actions[1].existingId)
        assertEquals(RustDataCore.SubscriptionActionKind.REORDER, plan.actions[1].action)
        assertTrue(plan.deletionIds.isEmpty())
    }

    @Test
    fun preservesNodeIdWhenSubscriptionRenamesIt() {
        val plan = RustDataCore.planSubscriptionUpdate(
            incoming = listOf(RustDataCore.SubscriptionIncoming("new name", "same endpoint")),
            existing = listOf(
                RustDataCore.SubscriptionExisting(7L, "old name", 1L, "same endpoint")
            ),
        )

        assertEquals(7L, plan.actions.single().existingId)
        assertEquals(RustDataCore.SubscriptionActionKind.UPDATE, plan.actions.single().action)
        assertTrue(plan.deletionIds.isEmpty())
    }

    @Test
    fun matchesEqualRenamedDuplicatesByPersistedOrderDeterministically() {
        val incoming = listOf(
            RustDataCore.SubscriptionIncoming("renamed first", "duplicate"),
            RustDataCore.SubscriptionIncoming("renamed second", "duplicate"),
        )
        val firstPlan = RustDataCore.planSubscriptionUpdate(
            incoming = incoming,
            existing = listOf(
                RustDataCore.SubscriptionExisting(11L, "old second", 2L, "duplicate"),
                RustDataCore.SubscriptionExisting(12L, "old first", 1L, "duplicate"),
            ),
        )
        val shuffledPlan = RustDataCore.planSubscriptionUpdate(
            incoming = incoming,
            existing = listOf(
                RustDataCore.SubscriptionExisting(12L, "old first", 1L, "duplicate"),
                RustDataCore.SubscriptionExisting(11L, "old second", 2L, "duplicate"),
            ),
        )

        assertEquals(listOf(12L, 11L), firstPlan.actions.map { it.existingId })
        assertEquals(firstPlan, shuffledPlan)
        assertTrue(firstPlan.deletionIds.isEmpty())
    }

    @Test
    fun identifiesContentAndOrderChangesAndSelectionFallback() {
        val plan = RustDataCore.planSubscriptionUpdate(
            incoming = listOf(
                RustDataCore.SubscriptionIncoming("one", "one changed"),
                RustDataCore.SubscriptionIncoming("two", "two"),
            ),
            existing = listOf(
                RustDataCore.SubscriptionExisting(1L, "one", 1L, "one old"),
                RustDataCore.SubscriptionExisting(2L, "two", 3L, "two"),
            ),
        )

        assertEquals(RustDataCore.SubscriptionActionKind.UPDATE, plan.actions[0].action)
        assertEquals(RustDataCore.SubscriptionActionKind.REORDER, plan.actions[1].action)
        assertTrue(RustDataCore.requiresSubscriptionSelectionFallback(false))
        assertFalse(RustDataCore.requiresSubscriptionSelectionFallback(true))
    }
}
