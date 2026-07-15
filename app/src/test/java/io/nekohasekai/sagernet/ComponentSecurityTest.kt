package io.nekohasekai.sagernet

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentSecurityTest {
    @Test
    fun defaultConnectionTestUsesTls() {
        assertTrue(CONNECTION_TEST_URL.startsWith("https://"))
    }

    @Test
    fun onlyBootBroadcastsAreAccepted() {
        assertTrue(isExpectedBootAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(isExpectedBootAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertTrue(isExpectedBootAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(isExpectedBootAction("attacker.intent.START"))
        assertFalse(isExpectedBootAction(null))
    }

}
