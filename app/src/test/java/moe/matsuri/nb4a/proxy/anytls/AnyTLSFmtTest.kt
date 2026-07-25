package moe.matsuri.nb4a.proxy.anytls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnyTLSFmtTest {
    @Test
    fun parsesCaseInsensitiveSchemeAndRepresentableParameters() {
        val profile = parseAnytls("ANYTLS://password@example.com:8443/?sni=edge.example&insecure=1&fp=chrome#Edge")

        assertEquals("password", profile.password)
        assertEquals("example.com", profile.serverAddress)
        assertEquals(8443, profile.serverPort)
        assertEquals("edge.example", profile.sni)
        assertTrue(profile.allowInsecure)
        assertEquals("chrome", profile.utlsFingerprint)
        assertEquals("Edge", profile.name)
    }

    @Test
    fun rejectsAmbiguousOrLossyParameters() {
        listOf(
            "anytls://password@example.com/?unknown=value",
            "anytls://password@example.com/?sni=one&sni=two",
            "anytls://password@example.com/?insecure=maybe",
            "anytls://@example.com/",
        ).forEach { link ->
            val failure = runCatching { parseAnytls(link) }.exceptionOrNull()
            assertTrue("expected $link to be rejected", failure is IllegalArgumentException || failure is IllegalStateException)
        }
    }
}
