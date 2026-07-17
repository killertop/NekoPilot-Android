package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsTest {
    @Test
    fun redactsProxyLinksAndCredentials() {
        val sanitized = sanitizeLog(
            "trojan://secret@example.com:443?password=hunter2 " +
                "https://user:pass@example.com/path?token=abc " +
                "{\"private_key\":\"key-material\",\"uuid\":\"id\"}"
        )
        for (secret in listOf("hunter2", "user:pass", "token=abc", "key-material", "\"uuid\":\"id\"")) {
            assertFalse(sanitized.contains(secret))
        }
        assertTrue(sanitized.contains("[redacted"))
    }
}
