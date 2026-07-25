package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.core.SubscriptionDataCore
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.MAX_PROFILE_ENTRIES
import io.nekohasekai.sagernet.ktx.MAX_PROFILE_LINK_CHARS
import io.nekohasekai.sagernet.ktx.parseProxies
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class KotlinProfileCodecTest {

    @Test
    fun preservesImportedConnectionFields() {
        val profiles = parseSerializedProfiles(
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

    @Test
    fun refusesRawConfigurationBeforeItCanEnterAnExternalImportPath() {
        assertThrows(ExternalRawConfigImportException::class.java) {
            requireSafeExternalProfile(ConfigBean())
        }
        assertThrows(ExternalRawConfigImportException::class.java) {
            parseExternalProfileLink("sn://config?not-a-valid-payload")
        }
        val safeNode = TrojanBean()
        assertTrue(requireSafeExternalProfile(safeNode) === safeNode)
    }

    @Test
    fun rejectsDocumentBudgetsBeforeParsingOrCreatingProfiles() {
        val oversizedLink = "unsupported://" + "a".repeat(MAX_PROFILE_LINK_CHARS)
        assertThrows(IllegalArgumentException::class.java) {
            parseProfileDocument(oversizedLink)
        }

        val tooManySubscriptionLinks = buildString {
            repeat(SubscriptionDataCore.MAX_SUBSCRIPTION_PROFILES + 1) {
                append("unsupported://node\n")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSubscriptionDocument(tooManySubscriptionLinks)
        }

        val tooManyImportLinks = buildString {
            repeat(MAX_PROFILE_ENTRIES + 1) {
                append("unsupported://node\n")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseProfileDocument(tooManyImportLinks)
        }
    }

    @Test
    fun rejectsOversizedBase64AndDeepLinkTokenListsBeforeDecodingOrParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSubscriptionDocument("A".repeat(MAX_BASE64_PROFILE_DOCUMENT_CHARS + 1))
        }

        val failure = runCatching {
            runBlocking { parseProxies("node ".repeat(MAX_PROFILE_ENTRIES + 1)) }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
