package io.nekohasekai.sagernet.fmt

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

@RunWith(AndroidJUnit4::class)
class UniversalFmtTest {
    @Test
    fun sharedSubscriptionPayloadRoundTripsThroughExternalDecoder() {
        val source = ProxyGroup(
            name = "Shared airport",
            type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().apply {
                link = "https://provider.example/sub?token=redacted"
            },
        )
        val payload = source.toUniversalLink().substringAfter('?')

        val restored = decodeExternalSubscriptionPayload(payload)

        assertEquals("Shared airport", restored.name)
        assertEquals(GroupType.SUBSCRIPTION, restored.type)
        assertEquals("https://provider.example/sub?token=redacted", restored.subscription?.link)
    }

    @Test
    fun externalSharedSubscriptionPayloadUsesUniversalLinkSizeBudget() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            decodeExternalSubscriptionPayload(
                "!".repeat(MAX_EXTERNAL_UNIVERSAL_PAYLOAD_CHARS + 1),
            )
        }

        assertEquals("Universal link payload is too large", error.message)
    }

    @Test
    fun externalUniversalPayloadIsRejectedBeforeBase64Decode() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parseExternalProfileLink("sn://socks?" + "!".repeat(MAX_EXTERNAL_UNIVERSAL_PAYLOAD_CHARS + 1))
        }

        assertEquals("Universal link payload is too large", error.message)
    }

    @Test
    fun externalUniversalPayloadRejects32MiBZlibExpansionBeforeKryoDeserialization() {
        val compressedPayload = zlibAndEncode(ByteArray(32 * 1024 * 1024))
        assertTrue(compressedPayload.length <= MAX_EXTERNAL_UNIVERSAL_PAYLOAD_CHARS)

        val error = assertThrows(IllegalArgumentException::class.java) {
            parseExternalProfileLink("sn://socks?$compressedPayload")
        }

        // zlibDecompress rejects the output before ProxyEntity invokes Kryo.
        assertEquals("Zlib payload is too large", error.message)
    }

    @Test
    fun trustedUniversalParserKeepsItsExistingLargerCompatibilityBudget() {
        val expected = ConfigBean().apply {
            config = "x".repeat(MAX_EXTERNAL_UNIVERSAL_DECOMPRESSED_BYTES + 1)
        }

        val restored = parseUniversal(expected.toUniversalLink()) as ConfigBean

        assertEquals(expected.config, restored.config)
    }

    private fun zlibAndEncode(input: ByteArray): String {
        val compressed = ByteArrayOutputStream().use { output ->
            DeflaterOutputStream(output, Deflater(Deflater.BEST_COMPRESSION)).use { stream ->
                stream.write(input)
            }
            output.toByteArray()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    }
}
