package io.nekohasekai.sagernet.core

import io.nekohasekai.sagernet.fmt.subscriptionSkippedNames
import libcore.Libcore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoDataCoreTest {
    @Test
    fun matchesDuplicateNamesWithoutDiscardingEitherNode() {
        val plan = GoDataCore.planSubscriptionUpdate(
            incoming = listOf(
                GoDataCore.SubscriptionIncoming("same", "second"),
                GoDataCore.SubscriptionIncoming("same", "first"),
                GoDataCore.SubscriptionIncoming("new", "new"),
            ),
            existing = listOf(
                GoDataCore.SubscriptionExisting(1L, "same", 2L, "first"),
                GoDataCore.SubscriptionExisting(2L, "same", 1L, "second"),
                GoDataCore.SubscriptionExisting(3L, "old", 3L, "old"),
            ),
        )

        assertEquals(2L, plan.actions[0].existingId)
        assertEquals(GoDataCore.SubscriptionActionKind.UNCHANGED, plan.actions[0].action)
        assertEquals(1L, plan.actions[1].existingId)
        assertEquals(GoDataCore.SubscriptionActionKind.UNCHANGED, plan.actions[1].action)
        assertEquals(GoDataCore.SubscriptionActionKind.ADD, plan.actions[2].action)
        assertEquals(listOf(3L), plan.deletionIds)
    }

    @Test
    fun preservesDuplicateNodeIdentityWhenSubscriptionOrderChanges() {
        val plan = GoDataCore.planSubscriptionUpdate(
            incoming = listOf(
                GoDataCore.SubscriptionIncoming("same", "second"),
                GoDataCore.SubscriptionIncoming("same", "first"),
            ),
            existing = listOf(
                GoDataCore.SubscriptionExisting(1L, "same", 1L, "first"),
                GoDataCore.SubscriptionExisting(2L, "same", 2L, "second"),
            ),
        )

        assertEquals(2L, plan.actions[0].existingId)
        assertEquals(GoDataCore.SubscriptionActionKind.REORDER, plan.actions[0].action)
        assertEquals(1L, plan.actions[1].existingId)
        assertEquals(GoDataCore.SubscriptionActionKind.REORDER, plan.actions[1].action)
        assertTrue(plan.deletionIds.isEmpty())
    }

    @Test
    fun preservesNodeIdWhenSubscriptionRenamesIt() {
        val plan = GoDataCore.planSubscriptionUpdate(
            incoming = listOf(GoDataCore.SubscriptionIncoming("new name", "same endpoint")),
            existing = listOf(
                GoDataCore.SubscriptionExisting(7L, "old name", 1L, "same endpoint")
            ),
        )

        assertEquals(7L, plan.actions.single().existingId)
        assertEquals(GoDataCore.SubscriptionActionKind.UPDATE, plan.actions.single().action)
        assertTrue(plan.deletionIds.isEmpty())
    }

    @Test
    fun matchesEqualRenamedDuplicatesByPersistedOrderDeterministically() {
        val incoming = listOf(
            GoDataCore.SubscriptionIncoming("renamed first", "duplicate"),
            GoDataCore.SubscriptionIncoming("renamed second", "duplicate"),
        )
        val firstPlan = GoDataCore.planSubscriptionUpdate(
            incoming = incoming,
            existing = listOf(
                GoDataCore.SubscriptionExisting(11L, "old second", 2L, "duplicate"),
                GoDataCore.SubscriptionExisting(12L, "old first", 1L, "duplicate"),
            ),
        )
        val shuffledPlan = GoDataCore.planSubscriptionUpdate(
            incoming = incoming,
            existing = listOf(
                GoDataCore.SubscriptionExisting(12L, "old first", 1L, "duplicate"),
                GoDataCore.SubscriptionExisting(11L, "old second", 2L, "duplicate"),
            ),
        )

        assertEquals(listOf(12L, 11L), firstPlan.actions.map { it.existingId })
        assertEquals(firstPlan, shuffledPlan)
        assertTrue(firstPlan.deletionIds.isEmpty())
    }

    @Test
    fun identifiesContentAndOrderChangesAndSelectionFallback() {
        val plan = GoDataCore.planSubscriptionUpdate(
            incoming = listOf(
                GoDataCore.SubscriptionIncoming("one", "one changed"),
                GoDataCore.SubscriptionIncoming("two", "two"),
            ),
            existing = listOf(
                GoDataCore.SubscriptionExisting(1L, "one", 1L, "one old"),
                GoDataCore.SubscriptionExisting(2L, "two", 3L, "two"),
            ),
        )

        assertEquals(GoDataCore.SubscriptionActionKind.UPDATE, plan.actions[0].action)
        assertEquals(GoDataCore.SubscriptionActionKind.REORDER, plan.actions[1].action)
        assertTrue(GoDataCore.requiresSubscriptionSelectionFallback(false))
        assertFalse(GoDataCore.requiresSubscriptionSelectionFallback(true))
    }

    @Test
    fun boundsLargeAirportAndKeepsSelectedAndKnownFastNodes() {
        val candidates = (1L..10_000L).map { id ->
            GoDataCore.AutoSwitchCandidate(
                id = id,
                status = if (id <= 100L) 1 else 0,
                latencyMs = if (id <= 100L) id.toInt() else 0,
            )
        }
        val selection = GoDataCore.planAutoSwitchCandidates(
            candidates = candidates,
            selectedId = 9_999L,
            explorationOffset = 0,
        )

        assertEquals(64, selection.ids.size)
        assertEquals(9_999L, selection.ids.first())
        assertEquals((1L..48L).toList(), selection.ids.drop(1).take(48))
    }

    @Test
    fun rotatesAutoSwitchExplorationAndChoosesBestLatencyDeterministically() {
        val candidates = (1L..113L).map { id ->
            GoDataCore.AutoSwitchCandidate(
                id = id,
                status = if (id <= 49L) 1 else 0,
                latencyMs = if (id <= 49L) id.toInt() else 0,
            )
        }
        var offset = 0
        val explored = linkedSetOf<Long>()
        repeat(5) {
            val selection = GoDataCore.planAutoSwitchCandidates(
                candidates = candidates,
                selectedId = 1L,
                explorationOffset = offset,
            )
            explored += selection.ids.drop(49)
            offset = selection.nextExplorationOffset
        }

        assertEquals((50L..113L).toSet(), explored)
        assertEquals(7L, GoDataCore.selectBestLatency(mapOf(9L to 50, 7L to 50, 3L to 0)))
        assertEquals(null, GoDataCore.selectBestLatency(mapOf(3L to 0)))
    }

    @Test
    fun malformedJsonIsRejectedAcrossTheGomobileBoundary() {
        assertGoProxyError("decode subscription update request") {
            Libcore.planSubscriptionUpdate("{")
        }
        assertGoProxyError("decode auto-switch request") {
            Libcore.planAutoSwitchCandidates("[]")
        }
        assertGoProxyError("decode latency results") {
            Libcore.selectBestLatency("{")
        }
    }

    @Test
    fun kotlinAdaptersPreserveGoInputRejections() {
        assertGoProxyError("empty incoming identity") {
            GoDataCore.planSubscriptionUpdate(
                incoming = listOf(GoDataCore.SubscriptionIncoming("node", "")),
                existing = emptyList(),
            )
        }
        assertGoProxyError("duplicate auto-switch candidate ID") {
            GoDataCore.planAutoSwitchCandidates(
                candidates = listOf(
                    GoDataCore.AutoSwitchCandidate(1L, 0, 0),
                    GoDataCore.AutoSwitchCandidate(1L, 1, 20),
                ),
                selectedId = 1L,
                explorationOffset = 0,
            )
        }
        assertGoProxyError("invalid latency result ID") {
            GoDataCore.selectBestLatency(mapOf(-1L to 20))
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

    private fun assertGoProxyError(expectedMessage: String, block: () -> Unit) {
        val error = try {
            block()
            throw AssertionError("Expected Go to reject input containing $expectedMessage")
        } catch (error: Exception) {
            error
        }
        assertTrue(
            "Expected gomobile proxyerror, got ${error.javaClass.name}",
            error.javaClass.name.endsWith("proxyerror"),
        )
        assertTrue(
            "Unexpected Go error: ${error.message}",
            error.message.orEmpty().contains(expectedMessage),
        )
    }
}
