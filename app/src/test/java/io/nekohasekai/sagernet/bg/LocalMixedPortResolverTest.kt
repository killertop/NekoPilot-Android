package io.nekohasekai.sagernet.bg

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMixedPortResolverTest {
    @Test
    fun keepsPreferredPortWhenItIsAvailable() {
        val preferred = ServerSocket(0).use(ServerSocket::getLocalPort)

        assertEquals(preferred, resolveAvailableMixedPort(preferred, allowLanAccess = false))
    }

    @Test
    fun selectsNekoPilotPortWhenPreferredPortIsOccupied() {
        ServerSocket().use { occupied ->
            occupied.reuseAddress = false
            occupied.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))

            val resolved = resolveAvailableMixedPort(occupied.localPort, allowLanAccess = false)

            assertNotEquals(occupied.localPort, resolved)
            assertTrue(resolved in 20_880..20_979)
        }
    }
}
