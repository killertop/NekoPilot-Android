package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.ProxyEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSnapshotPolicyTest {
    private fun profile(
        id: Long = 7L,
        groupId: Long = 3L,
        type: Int = ProxyEntity.TYPE_SOCKS,
        revision: Long = 11L,
    ) = ProxyEntity(
        id = id,
        groupId = groupId,
        type = type,
        configRevision = revision,
    )

    @Test
    fun acceptsOnlyTheSelectedUnchangedSnapshot() {
        val started = profile()

        assertTrue(connectionSnapshotMatches(started, 7L, profile()))
        assertFalse(connectionSnapshotMatches(started, 8L, profile()))
        assertFalse(connectionSnapshotMatches(started, 7L, profile(revision = 12L)))
        assertFalse(connectionSnapshotMatches(started, 7L, profile(type = ProxyEntity.TYPE_HTTP)))
        assertFalse(connectionSnapshotMatches(started, 7L, null))
    }
}
