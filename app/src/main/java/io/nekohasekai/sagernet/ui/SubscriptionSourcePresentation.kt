package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.group.SubscriptionFailureRecord

internal data class SubscriptionSourceRow(
    val groupId: Long,
    val displayName: String,
    val nodeCount: Int,
    val lastUpdatedSeconds: Long,
    val updating: Boolean,
    val lastFailure: SubscriptionFailureRecord? = null,
) {
    fun updateState(nowMillis: Long): SubscriptionUpdateState = when {
        updating -> SubscriptionUpdateState.Updating
        lastFailure != null && lastFailure.occurredAtSeconds >= lastUpdatedSeconds ->
            SubscriptionUpdateState.Failed(lastFailure)
        lastUpdatedSeconds <= 0L -> SubscriptionUpdateState.NeverUpdated
        nowMillis - lastUpdatedSeconds * 1000L < JUST_UPDATED_WINDOW_MS ->
            SubscriptionUpdateState.JustUpdated
        else -> SubscriptionUpdateState.UpdatedAt(lastUpdatedSeconds * 1000L)
    }

    private companion object {
        const val JUST_UPDATED_WINDOW_MS = 60_000L
    }
}

internal sealed interface SubscriptionUpdateState {
    data object Updating : SubscriptionUpdateState
    data object NeverUpdated : SubscriptionUpdateState
    data object JustUpdated : SubscriptionUpdateState
    data class UpdatedAt(val timestampMillis: Long) : SubscriptionUpdateState
    data class Failed(val record: SubscriptionFailureRecord) : SubscriptionUpdateState
}

internal fun isSubscriptionUpdating(
    groupId: Long,
    updatingGroupIds: Set<Long>,
): Boolean = groupId in updatingGroupIds
