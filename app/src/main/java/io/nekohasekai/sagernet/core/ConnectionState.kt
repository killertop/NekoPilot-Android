package io.nekohasekai.sagernet.core

/**
 * Process-neutral connection lifecycle shared by the VPN service and its UI projections.
 *
 * [wireValue] is explicit because the state crosses an AIDL boundary. Reordering enum entries must
 * never silently change the protocol between the main process and the VPN process.
 */
enum class ConnectionState(
    val wireValue: Int,
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    val started: Boolean = false,
    val connected: Boolean = false,
) {
    Idle(wireValue = 0, canStart = true),
    Preparing(wireValue = 1, canStop = true, started = true),
    Connecting(wireValue = 2, canStop = true, started = true),
    Connected(wireValue = 3, canStop = true, started = true, connected = true),
    Stopping(wireValue = 4, started = true),
    Error(wireValue = 5, canStart = true),
    ;

    companion object {
        private val byWireValue = entries.associateBy(ConnectionState::wireValue)

        fun fromWireValue(value: Int): ConnectionState? = byWireValue[value]
    }
}

/** A typed stop result prevents failure state and failure message from drifting apart. */
sealed interface ConnectionStopResult {
    data object Completed : ConnectionStopResult
    data class Failed(val message: String) : ConnectionStopResult
}
