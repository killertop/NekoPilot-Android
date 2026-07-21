package io.nekohasekai.sagernet.core

/** Deterministic, platform-neutral data decisions owned by Kotlin. */
internal object GoDataCore {
    const val MAX_SUBSCRIPTION_PROFILES = 10_000
    const val MAX_AUTO_SWITCH_CANDIDATES = 20_000
    private const val MAX_LATENCY_RESULTS = 1_024

    data class AutoSwitchCandidate(val id: Long, val status: Int, val latencyMs: Int)

    data class AutoSwitchSelection(
        val ids: List<Long>,
        val exploredCount: Int,
        val explorationPoolSize: Int,
        val nextExplorationOffset: Int,
    )

    data class SubscriptionIncoming(val name: String, val identity: String)

    data class SubscriptionExisting(
        val id: Long,
        val name: String,
        val userOrder: Long,
        val identity: String,
    )

    enum class SubscriptionActionKind { ADD, UPDATE, REORDER, UNCHANGED }

    data class SubscriptionAction(
        val incomingIndex: Int,
        val existingId: Long?,
        val action: SubscriptionActionKind,
        val userOrder: Long,
    )

    data class SubscriptionPlan(val actions: List<SubscriptionAction>, val deletionIds: List<Long>)

    fun planSubscriptionUpdate(
        incoming: List<SubscriptionIncoming>,
        existing: List<SubscriptionExisting>,
    ): SubscriptionPlan {
        require(incoming.size <= MAX_SUBSCRIPTION_PROFILES && existing.size <= MAX_SUBSCRIPTION_PROFILES) {
            "Subscription update contains too many profiles"
        }
        require(incoming.all { it.identity.isNotBlank() }) {
            "Subscription update contains an empty incoming identity"
        }
        require(existing.all { it.id > 0L && it.userOrder >= 0L && it.identity.isNotBlank() }) {
            "Subscription update contains an invalid existing profile"
        }
        require(existing.map(SubscriptionExisting::id).toSet().size == existing.size) {
            "Subscription update contains a duplicate existing profile ID"
        }

        val unused = existing.associateByTo(linkedMapOf(), SubscriptionExisting::id)
        fun indexBy(key: (SubscriptionExisting) -> String): Map<String, ArrayDeque<SubscriptionExisting>> =
            existing.groupBy(key).mapValuesTo(linkedMapOf()) { (_, values) ->
                ArrayDeque(values.sortedWith(compareBy(SubscriptionExisting::userOrder, SubscriptionExisting::id)))
            }
        val byName = indexBy(SubscriptionExisting::name)
        val byIdentity = indexBy(SubscriptionExisting::identity)
        val byIdentityAndName = existing.groupBy { it.identity to it.name }
            .mapValuesTo(linkedMapOf()) { (_, values) ->
                ArrayDeque(values.sortedWith(compareBy(SubscriptionExisting::userOrder, SubscriptionExisting::id)))
            }

        fun nextUnused(candidates: ArrayDeque<SubscriptionExisting>?): SubscriptionExisting? {
            while (candidates?.isNotEmpty() == true) {
                val candidate = candidates.first()
                if (unused.containsKey(candidate.id)) return candidate
                candidates.removeFirst()
            }
            return null
        }

        val actions = incoming.mapIndexed { index, profile ->
            val exact = nextUnused(byIdentityAndName[profile.identity to profile.name])
            val identityMatch = exact ?: nextUnused(byIdentity[profile.identity])
            val matched = identityMatch ?: nextUnused(byName[profile.name])
            val order = index.toLong() + 1L
            if (matched == null) {
                SubscriptionAction(index, null, SubscriptionActionKind.ADD, order)
            } else {
                unused.remove(matched.id)
                val action = when {
                    identityMatch == null || matched.name != profile.name -> SubscriptionActionKind.UPDATE
                    matched.userOrder != order -> SubscriptionActionKind.REORDER
                    else -> SubscriptionActionKind.UNCHANGED
                }
                SubscriptionAction(index, matched.id, action, order)
            }
        }
        return SubscriptionPlan(
            actions = actions,
            deletionIds = unused.values
                .sortedWith(compareBy(SubscriptionExisting::userOrder, SubscriptionExisting::id))
                .map(SubscriptionExisting::id),
        )
    }

    fun requiresSubscriptionSelectionFallback(selectedPresent: Boolean): Boolean = !selectedPresent

    fun planAutoSwitchCandidates(
        candidates: List<AutoSwitchCandidate>,
        selectedId: Long,
        explorationOffset: Int,
        limit: Int = 64,
        knownFastLimit: Int = 48,
    ): AutoSwitchSelection {
        require(candidates.size <= MAX_AUTO_SWITCH_CANDIDATES) {
            "Automatic node selection contains too many candidates"
        }
        require(selectedId >= 0L) { "Invalid selected profile ID" }
        require(limit in 1..1_024) { "Invalid automatic node selection limit" }
        require(knownFastLimit in 0 until limit) { "Invalid known-fast node selection limit" }
        require(candidates.all { it.id > 0L }) { "Invalid automatic node selection candidate ID" }
        require(candidates.map(AutoSwitchCandidate::id).toSet().size == candidates.size) {
            "Duplicate automatic node selection candidate ID"
        }
        if (candidates.size <= limit) {
            return AutoSwitchSelection(candidates.map(AutoSwitchCandidate::id), 0, 0, 0)
        }

        val selectedPresent = candidates.any { it.id == selectedId }
        val knownFast = candidates.asSequence()
            .filter { it.id != selectedId && it.status == 1 && it.latencyMs > 0 }
            .sortedWith(compareBy(AutoSwitchCandidate::latencyMs, AutoSwitchCandidate::id))
            .take(knownFastLimit)
            .toList()
        val ids = LinkedHashSet<Long>(limit)
        if (selectedPresent) ids += selectedId
        knownFast.forEach { ids += it.id }
        val unexplored = candidates.asSequence().map(AutoSwitchCandidate::id)
            .filterNot(ids::contains).sorted().toList()
        val fixedCount = ids.size
        if (unexplored.isNotEmpty()) {
            var offset = Math.floorMod(explorationOffset, unexplored.size)
            while (ids.size < limit && ids.size < candidates.size) {
                ids += unexplored[offset]
                offset = (offset + 1) % unexplored.size
            }
        }
        val exploredCount = ids.size - fixedCount
        val nextOffset = if (unexplored.isEmpty()) 0 else {
            (Math.floorMod(explorationOffset, unexplored.size) + exploredCount) % unexplored.size
        }
        return AutoSwitchSelection(ids.toList(), exploredCount, unexplored.size, nextOffset)
    }

    fun selectBestLatency(results: Map<Long, Int>): Long? {
        require(results.size <= MAX_LATENCY_RESULTS) { "Too many latency results" }
        require(results.keys.all { it > 0L }) { "Invalid latency result ID" }
        return results.asSequence()
            .filter { (_, latencyMs) -> latencyMs > 0 }
            .minWithOrNull(compareBy<Map.Entry<Long, Int>>({ it.value }, { it.key }))
            ?.key
    }

}
