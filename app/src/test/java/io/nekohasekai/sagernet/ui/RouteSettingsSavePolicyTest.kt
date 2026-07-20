package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSettingsSavePolicyTest {

    @Test
    fun unchangedExistingRuleClosesWithoutBeingTreatedAsInvalid() {
        assertTrue(shouldCloseRouteEditorWithoutSaving(editingId = 8L, dirty = false))
        assertFalse(shouldCloseRouteEditorWithoutSaving(editingId = 8L, dirty = true))
        assertFalse(shouldCloseRouteEditorWithoutSaving(editingId = 0L, dirty = false))
    }
}
