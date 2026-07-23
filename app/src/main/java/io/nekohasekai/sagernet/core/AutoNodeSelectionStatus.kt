package io.nekohasekai.sagernet.core

import org.json.JSONObject

enum class AutoNodeSelectionPhase {
    RECOVERING,
    SWITCHED,
    FAILED,
}

/**
 * Process-neutral projection of automatic node selection.
 *
 * The JSON shape is persisted in the shared configuration store, so encoding stays explicit and
 * backward compatible while both the VPN process and UI depend on this core model.
 */
data class AutoNodeSelectionStatus(
    val profileId: Long,
    val phase: AutoNodeSelectionPhase,
    val latencyMs: Int = 0,
    val until: Long = 0L,
) {
    fun encode(): String = JSONObject().apply {
        put(JSON_PROFILE_ID, profileId)
        put(JSON_PHASE, phase.name)
        put(JSON_LATENCY, latencyMs)
        put(JSON_UNTIL, until)
    }.toString()

    companion object {
        private const val JSON_PROFILE_ID = "profileId"
        private const val JSON_PHASE = "phase"
        private const val JSON_LATENCY = "latencyMs"
        private const val JSON_UNTIL = "until"

        fun decode(value: String): AutoNodeSelectionStatus? = value
            .takeIf(String::isNotBlank)
            ?.let { encoded ->
                runCatching {
                    val json = JSONObject(encoded)
                    AutoNodeSelectionStatus(
                        profileId = json.getLong(JSON_PROFILE_ID),
                        phase = AutoNodeSelectionPhase.valueOf(json.getString(JSON_PHASE)),
                        latencyMs = json.optInt(JSON_LATENCY).coerceAtLeast(0),
                        until = json.optLong(JSON_UNTIL).coerceAtLeast(0L),
                    ).takeIf { it.profileId > 0L }
                }.getOrNull()
            }
    }
}
