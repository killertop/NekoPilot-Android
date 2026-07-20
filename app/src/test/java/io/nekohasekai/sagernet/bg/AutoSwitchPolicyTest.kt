package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSwitchPolicyTest {
    @Test
    fun choosesLowestPositiveLatencyAcrossGroups() {
        assertEquals(22L, AutoSwitchPolicy.best(mapOf(11L to 180, 22L to 42, 33L to 90)))
    }

    @Test
    fun ignoresFailedMeasurements() {
        assertEquals(3L, AutoSwitchPolicy.best(mapOf(1L to -1, 2L to 0, 3L to 80)))
        assertNull(AutoSwitchPolicy.best(mapOf(1L to -1, 2L to 0)))
    }

    @Test
    fun resolvesEqualLatencyDeterministically() {
        assertEquals(7L, AutoSwitchPolicy.best(mapOf(9L to 50, 7L to 50)))
    }

    @Test
    fun excludesCandidatesRemovedBySubscriptionRefresh() {
        assertEquals(
            listOf(11L, 33L),
            AutoSwitchPolicy.liveCandidateIds(
                candidateIds = listOf(11L, 22L, 33L),
                existingIds = setOf(33L, 11L, 44L),
            ),
        )
    }

    @Test
    fun boundsLargeAirportAndKeepsSelectedAndKnownFastNodes() {
        val candidates = (1L..10_000L).map { id ->
            AutoSwitchPolicy.Candidate(
                id = id,
                status = if (id <= 100L) 1 else 0,
                latencyMs = if (id <= 100L) id.toInt() else 0,
            )
        }
        val selectedId = 9_999L
        val ids = AutoSwitchPolicy.boundedCandidateIds(candidates, selectedId, 0)

        assertEquals(64, ids.size)
        assertEquals(selectedId, ids.first())
        assertEquals((1L..48L).toList(), ids.drop(1).take(48))
    }

    @Test
    fun rotatesExplorationSliceAcrossRuns() {
        val candidates = (1L..1_000L).map { AutoSwitchPolicy.Candidate(it, 0, 0) }
        val first = AutoSwitchPolicy.boundedCandidateIds(candidates, 1L, 0).toSet()
        val second = AutoSwitchPolicy.boundedCandidateIds(candidates, 1L, 64).toSet()

        assertEquals(setOf(1L), first intersect second)
    }

    @Test
    fun explorationCursorAdvancesByTheActualExplorationWindow() {
        val candidates = (1L..113L).map { id ->
            AutoSwitchPolicy.Candidate(
                id = id,
                status = if (id <= 49L) 1 else 0,
                latencyMs = if (id <= 49L) id.toInt() else 0,
            )
        }
        var offset = 0
        val explored = linkedSetOf<Long>()
        repeat(5) {
            val selection = AutoSwitchPolicy.boundedCandidates(candidates, 1L, offset)
            explored += selection.ids.drop(49)
            offset = AutoSwitchPolicy.nextExplorationOffset(offset, selection)
        }

        assertEquals((50L..113L).toSet(), explored)
    }
}
