package io.nekohasekai.sagernet.database

/**
 * A single persisted node-selection revision.
 *
 * The profile and group identifiers live in two preference rows for compatibility, but callers
 * must read, compare, and replace them as one value. This prevents a subscription update running
 * in `:bg` from restoring an old fallback after the user has selected another node in the main
 * process.
 */
data class ProxySelection(
    val profileId: Long,
    val groupId: Long,
    /** Raw rows are retained so compare-and-set distinguishes an absent key from a stored zero. */
    internal val persistedProfileId: Long? = profileId,
    internal val persistedGroupId: Long? = groupId,
) {
    companion object {
        fun fromPersisted(profileId: Long?, groupId: Long?) = ProxySelection(
            profileId = profileId ?: 0L,
            groupId = groupId ?: 0L,
            persistedProfileId = profileId,
            persistedGroupId = groupId,
        )
    }
}

/**
 * A long-running operation may repair a missing selection only if nobody changed either part of
 * the selection since the operation began and the selected profile is still absent.
 */
internal fun ProxySelection.mayRecoverFrom(
    expected: ProxySelection,
    selectedProfileExists: Boolean,
): Boolean = this == expected && !selectedProfileExists
