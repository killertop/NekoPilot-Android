package io.nekohasekai.sagernet.bg

import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceTypeTest {
    @Test
    fun usesVpnForegroundServiceType() {
        assertEquals(FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED, foregroundServiceType())
    }
}
