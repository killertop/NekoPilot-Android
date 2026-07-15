package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.yaml.snakeyaml.error.YAMLException

class SafeYamlTest {
    @Test
    fun parsesSubscriptionMap() {
        val parsed = SafeYaml.loadMap("proxies:\n  - name: test\n    port: 443")

        assertEquals("test", ((parsed["proxies"] as List<*>).first() as Map<*, *>)["name"])
    }

    @Test
    fun rejectsArbitraryClassTags() {
        assertThrows(YAMLException::class.java) {
            SafeYaml.loadMap("payload: !!java.net.URL [https://example.com]")
        }
    }

    @Test
    fun rejectsDuplicateKeys() {
        assertThrows(YAMLException::class.java) {
            SafeYaml.loadMap("proxies: []\nproxies: []")
        }
    }

    @Test
    fun rejectsNonMappingRoot() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeYaml.loadMap("- proxy")
        }
    }

    @Test
    fun rejectsNonStringRootKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeYaml.loadMap("1: proxy")
        }
    }
}
