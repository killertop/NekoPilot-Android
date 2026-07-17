package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Test

class GoProfileBridgeTest {

    @Test
    fun preservesImportedConnectionFields() {
        val profiles = parseGoProfiles(
            """[
              {"kind":"trojan","name":"Trojan","serverAddress":"trojan.example","serverPort":2053,"password":"secret","sni":"edge.example"},
              {"kind":"anytls","name":"AnyTLS","serverAddress":"anytls.example","serverPort":443,"password":"secret","sni":"edge.example"},
              {"kind":"vless","name":"VLESS","serverAddress":"vless.example","serverPort":8443,"uuid":"00000000-0000-0000-0000-000000000000","alterId":-1,"security":"tls","realityPubKey":"public-key"}
            ]"""
        )

        val trojan = profiles[0] as TrojanBean
        assertEquals("trojan.example", trojan.serverAddress)
        assertEquals(2053, trojan.serverPort)
        assertEquals("secret", trojan.password)

        val anyTls = profiles[1] as AnyTLSBean
        assertEquals("anytls.example", anyTls.serverAddress)
        assertEquals(443, anyTls.serverPort)
        assertEquals("secret", anyTls.password)

        val vless = profiles[2] as VMessBean
        assertEquals("vless.example", vless.serverAddress)
        assertEquals(8443, vless.serverPort)
        assertEquals(-1, vless.alterId)
        assertEquals("public-key", vless.realityPubKey)
    }
}
