package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsTest {
    @Test
    fun redactsUrlsCredentialsAndAddresses() {
        val message = "request=https://example.invalid/sub?token=do-not-log " +
            "password=do-not-log session 123e4567-e89b-12d3-a456-426614174000 " +
            "connect 203.0.113.7:443"

        val sanitized = Logs.sanitizeForLog(message)

        assertFalse(sanitized.contains("do-not-log"))
        assertFalse(sanitized.contains("example.invalid"))
        assertFalse(sanitized.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(sanitized.contains("203.0.113.7"))
        assertTrue(sanitized.contains("[redacted-url]"))
        assertTrue(sanitized.contains("[redacted]"))
        assertTrue(sanitized.contains("[redacted-uuid]"))
        assertTrue(sanitized.contains("[redacted-address]"))
    }

    @Test
    fun truncatesLargeDiagnostics() {
        val sanitized = Logs.sanitizeForLog("x".repeat(20_000))

        assertTrue(sanitized.length < 17_000)
        assertTrue(sanitized.endsWith("…[truncated]"))
    }
}
