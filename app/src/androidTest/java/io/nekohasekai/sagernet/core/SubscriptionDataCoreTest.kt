package io.nekohasekai.sagernet.core

import io.nekohasekai.sagernet.fmt.subscriptionSkippedNames
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDataCoreTest {
    @Test
    fun matchesDuplicateNamesWithoutDiscardingEitherNode() {
        val plan = SubscriptionDataCore.planSubscriptionUpdate(
            incoming = listOf(
                SubscriptionDataCore.SubscriptionIncoming("same", "second"),
                SubscriptionDataCore.SubscriptionIncoming("same", "first"),
                SubscriptionDataCore.SubscriptionIncoming("new", "new"),
            ),
            existing = listOf(
                SubscriptionDataCore.SubscriptionExisting(1L, "same", 2L, "first"),
                SubscriptionDataCore.SubscriptionExisting(2L, "same", 1L, "second"),
                SubscriptionDataCore.SubscriptionExisting(3L, "old", 3L, "old"),
            ),
        )

        assertEquals(2L, plan.actions[0].existingId)
        assertEquals(SubscriptionDataCore.SubscriptionActionKind.UNCHANGED, plan.actions[0].action)
        assertEquals(1L, plan.actions[1].existingId)
        assertEquals(SubscriptionDataCore.SubscriptionActionKind.UNCHANGED, plan.actions[1].action)
        assertEquals(SubscriptionDataCore.SubscriptionActionKind.ADD, plan.actions[2].action)
        assertEquals(listOf(3L), plan.deletionIds)
    }

    @Test
    fun preservesDuplicateNodeIdentityWhenSubscriptionOrderChanges() {
        val plan = SubscriptionDataCore.planSubscriptionUpdate(
            incoming = listOf(
                SubscriptionDataCore.SubscriptionIncoming("same", "second"),
                SubscriptionDataCore.SubscriptionIncoming("same", "first"),
            ),
            existing = listOf(
                SubscriptionDataCore.SubscriptionExisting(1L, "same", 1L, "first"),
                SubscriptionDataCore.SubscriptionExisting(2L, "same", 2L, "second"),
            ),
        )

        assertEquals(2L, plan.actions[0].existingId)
        assertEquals(SubscriptionDataCore.SubscriptionActionKind.REORDER, plan.actions[0].action)
        assertEquals(1L, plan.actions[1].existingId)
        assertEquals(SubscriptionDataCore.SubscriptionActionKind.REORDER, plan.actions[1].action)
        assertTrue(plan.deletionIds.isEmpty())
    }

    @Test
    fun preservesNodeIdWhenSubscriptionRenamesIt() {
        val plan = SubscriptionDataCore.planSubscriptionUpdate(
            incoming = listOf(SubscriptionDataCore.SubscriptionIncoming("new name", "same endpoint")),
            existing = listOf(
                SubscriptionDataCore.SubscriptionExisting(7L, "old name", 1L, "same endpoint")
            ),
        )

        assertEquals(7L, plan.actions.single().existingId)
        assertEquals(SubscriptionDataCore.SubscriptionActionKind.UPDATE, plan.actions.single().action)
        assertTrue(plan.deletionIds.isEmpty())
    }

    @Test
    fun matchesEqualRenamedDuplicatesByPersistedOrderDeterministically() {
        val incoming = listOf(
            SubscriptionDataCore.SubscriptionIncoming("renamed first", "duplicate"),
            SubscriptionDataCore.SubscriptionIncoming("renamed second", "duplicate"),
        )
        val firstPlan = SubscriptionDataCore.planSubscriptionUpdate(
            incoming = incoming,
            existing = listOf(
                SubscriptionDataCore.SubscriptionExisting(11L, "old second", 2L, "duplicate"),
                SubscriptionDataCore.SubscriptionExisting(12L, "old first", 1L, "duplicate"),
            ),
        )
        val shuffledPlan = SubscriptionDataCore.planSubscriptionUpdate(
            incoming = incoming,
            existing = listOf(
                SubscriptionDataCore.SubscriptionExisting(12L, "old first", 1L, "duplicate"),
                SubscriptionDataCore.SubscriptionExisting(11L, "old second", 2L, "duplicate"),
            ),
        )

        assertEquals(listOf(12L, 11L), firstPlan.actions.map { it.existingId })
        assertEquals(firstPlan, shuffledPlan)
        assertTrue(firstPlan.deletionIds.isEmpty())
    }

    @Test
    fun identifiesContentAndOrderChangesAndSelectionFallback() {
        val plan = SubscriptionDataCore.planSubscriptionUpdate(
            incoming = listOf(
                SubscriptionDataCore.SubscriptionIncoming("one", "one changed"),
                SubscriptionDataCore.SubscriptionIncoming("two", "two"),
            ),
            existing = listOf(
                SubscriptionDataCore.SubscriptionExisting(1L, "one", 1L, "one old"),
                SubscriptionDataCore.SubscriptionExisting(2L, "two", 3L, "two"),
            ),
        )

        assertEquals(SubscriptionDataCore.SubscriptionActionKind.UPDATE, plan.actions[0].action)
        assertEquals(SubscriptionDataCore.SubscriptionActionKind.REORDER, plan.actions[1].action)
        assertTrue(SubscriptionDataCore.requiresSubscriptionSelectionFallback(false))
        assertFalse(SubscriptionDataCore.requiresSubscriptionSelectionFallback(true))
    }

    @Test
    fun failoverUsesOnlyPreviouslySuccessfulNodesInLatencyOrder() {
        val candidates = listOf(
            SubscriptionDataCore.FailoverCandidate(1L, 1, 20),
            SubscriptionDataCore.FailoverCandidate(2L, 1, 70),
            SubscriptionDataCore.FailoverCandidate(3L, 3, 0),
            SubscriptionDataCore.FailoverCandidate(4L, 1, 45),
        )

        assertEquals(4L, SubscriptionDataCore.selectFailoverCandidate(1L, candidates))
        assertEquals(
            2L,
            SubscriptionDataCore.selectFailoverCandidate(1L, candidates, excludedIds = setOf(4L)),
        )
    }

    @Test
    fun kotlinDataCoreRejectsInvalidInputs() {
        assertKotlinInputError("empty incoming identity") {
            SubscriptionDataCore.planSubscriptionUpdate(
                incoming = listOf(SubscriptionDataCore.SubscriptionIncoming("node", "")),
                existing = emptyList(),
            )
        }
        assertKotlinInputError("duplicate failover candidate ID") {
            SubscriptionDataCore.selectFailoverCandidate(
                currentId = 1L,
                candidates = listOf(
                    SubscriptionDataCore.FailoverCandidate(1L, 0, 0),
                    SubscriptionDataCore.FailoverCandidate(1L, 1, 20),
                ),
            )
        }
        assertKotlinInputError("invalid failover candidate ID") {
            SubscriptionDataCore.selectFailoverCandidate(
                currentId = 1L,
                candidates = listOf(SubscriptionDataCore.FailoverCandidate(-1L, 1, 20)),
            )
        }
    }

    @Test
    fun subscriptionMetadataAcceptsLegacyNullButRejectsMalformedTypes() {
        assertEquals(0, subscriptionSkippedNames(JSONObject("{\"skippedNames\":null}")).length())

        listOf(JSONObject("{}"), JSONObject("{\"skippedNames\":\"none\"}"))
            .forEach { malformed ->
                val error = runCatching { subscriptionSkippedNames(malformed) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException || error is IllegalStateException)
            }
    }

    private fun assertKotlinInputError(expectedMessage: String, block: () -> Unit) {
        val error = try {
            block()
            throw AssertionError("Expected Kotlin to reject input containing $expectedMessage")
        } catch (error: Exception) {
            error
        }
        assertTrue(
            "Unexpected Kotlin error: ${error.message}",
            error.message.orEmpty().contains(expectedMessage, ignoreCase = true),
        )
    }
}
