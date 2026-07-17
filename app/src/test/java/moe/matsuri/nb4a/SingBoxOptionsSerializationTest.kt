package moe.matsuri.nb4a

import com.google.gson.JsonParser
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SingBoxOptionsSerializationTest {
    @Test
    fun directSerializationAppliesCustomLayersInOrder() {
        val options = SingBoxOptions.MyOptions().apply {
            log = SingBoxOptions.LogOptions().apply { level = "info" }
            _hack_custom_config = """{"log":{"level":"warn"},"route":{"final":"direct"}}"""
            mergeCustomConfig("""{"log":{"level":"error"}}""")
        }
        val json = JsonParser.parseString(options.toJson()).asJsonObject
        assertEquals("error", json.getAsJsonObject("log").get("level").asString)
        assertEquals("direct", json.getAsJsonObject("route").get("final").asString)
    }

    @Test
    fun tunInboundUsesTheCurrentUnifiedAddressField() {
        val options = SingBoxOptions.Inbound_TunOptions().apply {
            type = "tun"
            address = listOf("172.19.0.1/28", "fdfe:dcba:9876::1/126")
        }

        val json = JsonParser.parseString(options.toJson()).asJsonObject
        assertEquals(2, json.getAsJsonArray("address").size())
        assertFalse(json.has("inet4_address"))
        assertFalse(json.has("inet6_address"))
        assertFalse(json.has("endpoint_independent_nat"))
    }

    @Test
    fun anyTlsImportPreservesTheCompleteNumericAddress() {
        val profile = parseAnytls("anytls://test-password@134.195.209.158:443#test")

        assertEquals("134.195.209.158", profile.serverAddress)
        assertEquals(443, profile.serverPort)
    }
}
