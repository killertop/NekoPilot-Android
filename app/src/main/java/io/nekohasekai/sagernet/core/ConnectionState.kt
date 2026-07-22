package io.nekohasekai.sagernet.core

/**
 * Process-neutral connection lifecycle shared by the VPN service and its UI projections.
 *
 * Keep the declaration order stable: the current Binder contract transports [ordinal].
 */
enum class ConnectionState(
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    val started: Boolean = false,
    val connected: Boolean = false,
) {
    Idle(canStart = true),
    Preparing(canStop = true, started = true),
    Connecting(canStop = true, started = true),
    Connected(canStop = true, started = true, connected = true),
    Stopping(started = true),
    Error(canStart = true),
    ;

    companion object {
        fun fromWireValue(value: Int): ConnectionState? = entries.getOrNull(value)
    }
}

enum class ConnectionStopResult {
    Completed,
    Failed,
}
