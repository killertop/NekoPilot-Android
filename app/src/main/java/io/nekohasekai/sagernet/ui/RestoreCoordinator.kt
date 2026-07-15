package io.nekohasekai.sagernet.ui

internal fun restoreWithRollback(
    applyProfilesAndRules: (() -> Unit)?,
    applySettings: (() -> Unit)?,
    rollbackProfilesAndRules: (() -> Unit)?,
) {
    var profilesAndRulesCommitted = false
    try {
        applyProfilesAndRules?.invoke()
        profilesAndRulesCommitted = applyProfilesAndRules != null
        applySettings?.invoke()
    } catch (error: Throwable) {
        if (profilesAndRulesCommitted) {
            try {
                rollbackProfilesAndRules?.invoke()
            } catch (rollbackError: Throwable) {
                error.addSuppressed(rollbackError)
            }
        }
        throw error
    }
}
