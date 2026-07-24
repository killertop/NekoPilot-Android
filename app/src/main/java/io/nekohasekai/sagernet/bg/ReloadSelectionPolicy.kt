package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxySelection

/**
 * A candidate that never becomes the active core must not stay selected in the UI. The actual
 * persistence step remains a compare-and-set, so this only expresses the stale-selection policy.
 */
internal fun shouldRestoreSelectionAfterCandidateFailure(
    candidate: ProxySelection,
    active: ProxySelection,
): Boolean = candidate.profileId != active.profileId

internal fun runtimeProfileMatches(current: ProxyEntity?, expected: ProxyEntity): Boolean =
    current != null && current.id == expected.id && current.groupId == expected.groupId &&
        current.type == expected.type && current.configRevision == expected.configRevision
