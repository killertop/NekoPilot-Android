package moe.matsuri.nb4a

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
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
}
