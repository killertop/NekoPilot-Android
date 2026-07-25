package io.nekohasekai.sagernet.fmt.naive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaiveFmtTest {
    @Test
    fun parsesHttpsAndQuicShareLinksWithoutDroppingSupportedFields() {
        val profile = parseNaive(
            "NAIVE+quic://user:secret@[2001:db8::1]:443?sni=edge.example&" +
                "cert=certificate&extra-headers=User-Agent%3A%20NekoPilot%0D%0AX-Test%3A%20yes&" +
                "insecure-concurrency=2#Naive",
        )

        assertEquals("quic", profile.proto)
        assertEquals("2001:db8::1", profile.serverAddress)
        assertEquals(443, profile.serverPort)
        assertEquals("user", profile.username)
        assertEquals("secret", profile.password)
        assertEquals("edge.example", profile.sni)
        assertEquals("certificate", profile.certificates)
        assertEquals("User-Agent: NekoPilot\nX-Test: yes", profile.extraHeaders)
        assertEquals(2, profile.insecureConcurrency)
        assertEquals("Naive", profile.name)
    }

    @Test
    fun rejectsUnknownOrLossyNaiveLinkFields() {
        val unsupportedProtocol = runCatching {
            parseNaive("naive+http://user:secret@naive.example:443")
        }.exceptionOrNull()
        val unsupportedParameter = runCatching {
            parseNaive("naive+https://user:secret@naive.example:443?padding=true")
        }.exceptionOrNull()
        val duplicateParameter = runCatching {
            parseNaive("naive+https://user:secret@naive.example:443?sni=one&sni=two")
        }.exceptionOrNull()

        assertTrue(unsupportedProtocol is IllegalStateException)
        assertTrue(unsupportedParameter is IllegalArgumentException)
        assertTrue(duplicateParameter is IllegalArgumentException)
    }
}
