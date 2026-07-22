package io.nekohasekai.sagernet

import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentSecurityTest {
    @Test
    fun defaultConnectionTestUsesGstaticNoContentEndpoint() {
        assertEquals("https://www.gstatic.com/generate_204", DEFAULT_CONNECTION_TEST_URL)
    }

}
