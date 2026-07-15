package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RestoreCoordinatorTest {
    @Test
    fun rollsBackCommittedProfilesWhenSettingsFail() {
        val events = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            restoreWithRollback(
                applyProfilesAndRules = { events += "apply profiles" },
                applySettings = {
                    events += "apply settings"
                    error("settings failed")
                },
                rollbackProfilesAndRules = { events += "rollback profiles" },
            )
        }
        assertEquals(
            listOf("apply profiles", "apply settings", "rollback profiles"),
            events,
        )
    }

    @Test
    fun preservesOriginalFailureWhenRollbackAlsoFails() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            restoreWithRollback(
                applyProfilesAndRules = {},
                applySettings = { throw IllegalArgumentException("import") },
                rollbackProfilesAndRules = { error("rollback") },
            )
        }
        assertEquals("import", error.message)
        assertEquals("rollback", error.suppressed.single().message)
    }
}
