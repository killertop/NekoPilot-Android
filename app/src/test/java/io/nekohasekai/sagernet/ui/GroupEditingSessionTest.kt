package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEditingSessionTest {

    @Test
    fun `restored activity reuses only its matching in-memory editing cache`() {
        assertTrue(canReuseGroupEditingCache("session-1", "session-1"))
        assertFalse(canReuseGroupEditingCache("session-1", "session-2"))
    }

    @Test
    fun `process restoration without an in-memory cache requires database hydration`() {
        assertFalse(canReuseGroupEditingCache("session-1", null))
        assertFalse(canReuseGroupEditingCache(null, "session-1"))
    }
}
