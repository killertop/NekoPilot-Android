package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.core.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPolicyReconnectPlanTest {
    @Test
    fun connectedVpnReconnectsOnlyWhenPackagePolicyChanged() {
        assertFalse(
            shouldReconnectForVpnPolicyChange(
                ConnectionState.Connected,
                activePackages = listOf("io.nekohasekai.sagernet", "com.example.one"),
                requestedPackages = listOf("io.nekohasekai.sagernet", "com.example.one"),
            ),
        )
        assertTrue(
            shouldReconnectForVpnPolicyChange(
                ConnectionState.Connected,
                activePackages = listOf("io.nekohasekai.sagernet", "com.example.one"),
                requestedPackages = listOf("io.nekohasekai.sagernet", "com.example.two"),
            ),
        )
    }

    @Test
    fun inFlightVpnReconnectsButIdleAndErrorDoNotStartTheService() {
        listOf(ConnectionState.Preparing, ConnectionState.Connecting, ConnectionState.Stopping)
            .forEach { state ->
                assertTrue(
                    shouldReconnectForVpnPolicyChange(
                        state,
                        activePackages = null,
                        requestedPackages = null,
                    ),
                )
            }
        listOf(ConnectionState.Idle, ConnectionState.Error).forEach { state ->
            assertFalse(
                shouldReconnectForVpnPolicyChange(
                    state,
                    activePackages = null,
                    requestedPackages = null,
                ),
            )
        }
    }
}
