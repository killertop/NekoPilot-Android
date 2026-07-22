package io.nekohasekai.sagernet.bg

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

private const val FIRST_NEKOPILOT_MIXED_PORT = 20_880
private const val LAST_NEKOPILOT_MIXED_PORT = 20_979

/**
 * Keeps the local mixed inbound usable when another proxy client still owns the inherited port.
 * Android revokes the previous VPN, but it does not guarantee that the other app immediately
 * releases its localhost listeners.
 */
internal fun resolveAvailableMixedPort(preferredPort: Int, allowLanAccess: Boolean): Int {
    val bindAddress = if (allowLanAccess) null else InetAddress.getByName("127.0.0.1")
    val candidates = sequenceOf(preferredPort) +
        (FIRST_NEKOPILOT_MIXED_PORT..LAST_NEKOPILOT_MIXED_PORT).asSequence()
            .filter { it != preferredPort }

    return candidates.firstOrNull { isTcpPortAvailable(it, bindAddress) }
        ?: ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(bindAddress, 0))
            socket.localPort
        }
}

private fun isTcpPortAvailable(port: Int, bindAddress: InetAddress?): Boolean = runCatching {
    ServerSocket().use { socket ->
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(bindAddress, port))
    }
}.isSuccess
