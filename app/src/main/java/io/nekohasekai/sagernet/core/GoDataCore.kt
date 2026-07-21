package io.nekohasekai.sagernet.core

import libcore.Libcore
import org.json.JSONArray
import org.json.JSONObject

/** Pure data decisions implemented by the already-loaded Go core. */
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
        val request = JSONObject()
            .put("incoming", JSONArray().apply {
                incoming.forEach { profile ->
                    put(JSONObject().put("name", profile.name).put("identity", profile.identity))
                }
            })
            .put("existing", JSONArray().apply {
                existing.forEach { profile ->
                    put(
                        JSONObject()
                            .put("id", profile.id)
                            .put("name", profile.name)
                            .put("user_order", profile.userOrder)
                            .put("identity", profile.identity)
                    )
                }
            })
        val response = JSONObject(Libcore.planSubscriptionUpdate(request.toString()))
        val actions = response.getJSONArray("actions")
        return SubscriptionPlan(
            actions = List(actions.length()) { index ->
                val action = actions.getJSONObject(index)
                SubscriptionAction(
                    incomingIndex = action.getInt("incoming_index"),
                    existingId = action.takeIf { it.has("existing_id") }?.getLong("existing_id"),
                    action = SubscriptionActionKind.valueOf(action.getString("action").uppercase()),
                    userOrder = action.getLong("user_order"),
                )
            },
            deletionIds = response.getJSONArray("deletion_ids").toLongList(),
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
        val request = JSONObject()
            .put("selected_id", selectedId)
            .put("exploration_offset", explorationOffset)
            .put("limit", limit)
            .put("known_fast_limit", knownFastLimit)
            .put("candidates", JSONArray().apply {
                candidates.forEach { candidate ->
                    put(
                        JSONObject()
                            .put("id", candidate.id)
                            .put("status", candidate.status)
                            .put("latency_ms", candidate.latencyMs)
                    )
                }
            })
        val response = JSONObject(Libcore.planAutoSwitchCandidates(request.toString()))
        return AutoSwitchSelection(
            ids = response.getJSONArray("ids").toLongList(),
            exploredCount = response.getInt("explored_count"),
            explorationPoolSize = response.getInt("exploration_pool_size"),
            nextExplorationOffset = response.getInt("next_exploration_offset"),
        )
    }

    fun selectBestLatency(results: Map<Long, Int>): Long? {
        require(results.size <= MAX_LATENCY_RESULTS) { "Too many latency results" }
        val request = JSONArray().apply {
            results.forEach { (id, latencyMs) ->
                put(JSONObject().put("id", id).put("latency_ms", latencyMs))
            }
        }
        return Libcore.selectBestLatency(request.toString()).takeIf { it > 0L }
    }

    private fun JSONArray.toLongList(): List<Long> = List(length()) { getLong(it) }
}
