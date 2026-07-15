package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeIniTest {
    @Test
    fun parsesRepeatedWireGuardSectionsAndValues() {
        val parsed = SafeIni.parse(
            """
            [Interface]
            Address = 10.0.0.2/32
            Address = fd00::2/128
            PrivateKey = private

            [Peer]
            PublicKey = first
            Endpoint = one.example:51820

            [Peer]
            PublicKey = second
            Endpoint = two.example:51820
            """.trimIndent()
        )

        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), parsed["Interface"]!!.single().values("Address"))
        assertEquals(listOf("first", "second"), parsed["Peer"]!!.map { it.value("PublicKey") })
    }

    @Test
    fun rejectsPropertiesBeforeASection() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeIni.parse("Address = 10.0.0.2/32")
        }
    }

    @Test
    fun rejectsOversizedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeIni.parse("x".repeat(1_000_001))
        }
    }
}
