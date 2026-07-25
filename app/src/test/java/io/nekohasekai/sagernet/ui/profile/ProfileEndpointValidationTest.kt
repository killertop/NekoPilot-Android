package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileEndpointValidationTest {
    @Test
    fun tuicPortIsValidatedBeforeProtocolSpecificFields() {
        val profile = TuicBean().apply {
            serverAddress = "tuic.example"
            serverPort = 0
            protocolVersion = 5
            uuid = "2dd61d93-75d8-4da4-ac0e-6aece7eac365"
            token = "password"
            congestionController = "bbr"
            udpRelayMode = "quic"
        }

        assertEquals(R.string.server_port_invalid, validateProfileEndpoint(profile))
    }

    @Test
    fun manualHysteriaFakeTcpCannotBeSaved() {
        val profile = HysteriaBean().apply {
            protocolVersion = 1
            serverAddress = "hysteria.example"
            serverPorts = "443"
            protocol = HysteriaBean.PROTOCOL_FAKETCP
            uploadMbps = 10
            downloadMbps = 50
            hopInterval = 10
        }

        assertEquals(R.string.hysteria_profile_invalid, validateProfileEndpoint(profile))
    }
}
