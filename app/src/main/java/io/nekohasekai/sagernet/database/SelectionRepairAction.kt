package io.nekohasekai.sagernet.database

sealed interface SelectionRepairAction {
    data object None : SelectionRepairAction
    data object StopService : SelectionRepairAction
    data class ReloadSelectedProfile(val profileId: Long) : SelectionRepairAction
}

internal fun selectionRepairAction(
    connectionStarted: Boolean,
    activeRemoved: Boolean,
    selectionRemoved: Boolean,
    selectedProfileId: Long,
): SelectionRepairAction = when {
    !activeRemoved && !selectionRemoved -> SelectionRepairAction.None
    connectionStarted && activeRemoved && !selectionRemoved ->
        SelectionRepairAction.ReloadSelectedProfile(selectedProfileId)
    else -> SelectionRepairAction.StopService
}
