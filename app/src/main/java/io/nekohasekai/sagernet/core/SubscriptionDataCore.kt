package io.nekohasekai.sagernet.core

/** Deterministic, platform-neutral data decisions owned by Kotlin. */
internal object SubscriptionDataCore {
    const val MAX_SUBSCRIPTION_PROFILES = 10_000
    const val MAX_FAILOVER_SESSION_CANDIDATES = 64

    data class FailoverCandidate(val id: Long, val status: Int, val latencyMs: Int)

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

    /** Picks the next already-tested node and never returns the current or an attempted node. */
    fun selectFailoverCandidate(
        currentId: Long,
        candidates: List<FailoverCandidate>,
        excludedIds: Set<Long> = emptySet(),
    ): Long? {
        require(currentId > 0L) { "Invalid current profile ID" }
        require(candidates.size <= MAX_FAILOVER_SESSION_CANDIDATES) {
            "Too many failover candidates"
        }
        require(candidates.all { it.id > 0L }) { "Invalid failover candidate ID" }
        require(candidates.map(FailoverCandidate::id).toSet().size == candidates.size) {
            "Duplicate failover candidate ID"
        }
        return candidates.asSequence()
            .filter {
                it.id != currentId && it.id !in excludedIds &&
                    it.status == 1 && it.latencyMs > 0
            }
            .minWithOrNull(compareBy<FailoverCandidate>({ it.latencyMs }, { it.id }))
            ?.id
    }

}
