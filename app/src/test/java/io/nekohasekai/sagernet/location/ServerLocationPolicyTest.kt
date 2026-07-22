package io.nekohasekai.sagernet.location

import java.net.InetAddress
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLocationPolicyTest {

    @Test
    fun `extractHost supports node display address formats`() {
        assertEquals("8.8.8.8", ServerLocationPolicy.extractHost("8.8.8.8:443"))
        assertEquals("example.com", ServerLocationPolicy.extractHost("Example.COM.:443"))
        assertEquals(
            "2001:4860:4860::8888",
            ServerLocationPolicy.extractHost("[2001:4860:4860::8888]:443"),
        )
        assertEquals(
            "hy.example",
            ServerLocationPolicy.extractHost("hy.example:443,8443,10000-20000"),
        )
        assertNull(ServerLocationPolicy.extractHost("Internal"))
        assertNull(ServerLocationPolicy.extractHost("  "))
    }

    @Test
    fun `extractHost normalizes international domains and URLs`() {
        assertEquals("xn--fsqu00a.xn--0zwm56d", ServerLocationPolicy.extractHost("例子.测试:443"))
        assertEquals("example.com", ServerLocationPolicy.extractHost("https://Example.com:443/path"))
    }

    @Test
    fun `isPublicAddress accepts globally routable addresses`() {
        assertTrue(ServerLocationPolicy.isPublicAddress(ip("8.8.8.8")))
        assertTrue(ServerLocationPolicy.isPublicAddress(ip("2606:4700:4700::1111")))
    }

    @Test
    fun `isPublicAddress rejects local and reserved addresses`() {
        listOf(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "192.0.2.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
            "::",
            "::1",
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
            "ff02::1",
        ).forEach { value ->
            assertFalse("Expected $value to be non-public", ServerLocationPolicy.isPublicAddress(ip(value)))
        }
    }

    @Test
    fun `parseCountryResponse accepts batch response and ignores missing entries`() {
        val result = ServerLocationPolicy.parseCountryResponse(
            """[
                {"ip":"8.8.8.8","country":"us"},
                {"ip":"1.1.1.1"},
                {"country":"CN"},
                {"ip":"9.9.9.9","country":"ZZ"},
                {"ip":"2001:4860:4860::8888","country":"US"},
                null
            ]""".trimIndent(),
        )

        assertEquals(
            mapOf("8.8.8.8" to "US", "2001:4860:4860::8888" to "US"),
            result,
        )
    }

    @Test
    fun `parseCountryResponse accepts single response and malformed input`() {
        assertEquals(
            mapOf("8.8.4.4" to "US"),
            ServerLocationPolicy.parseCountryResponse("""{"ip":"8.8.4.4","country":"US"}"""),
        )
        assertEquals(emptyMap<String, String>(), ServerLocationPolicy.parseCountryResponse("not-json"))
        assertEquals(emptyMap<String, String>(), ServerLocationPolicy.parseCountryResponse("[]"))
    }

    @Test
    fun `country names and decorated node names follow locale`() {
        assertEquals("United States", ServerLocationPolicy.localizedCountry("us", Locale.ENGLISH))
        assertEquals("美国", ServerLocationPolicy.localizedCountry("US", Locale.SIMPLIFIED_CHINESE))
        assertEquals(
            "United States · New York 01",
            ServerLocationPolicy.decorate("New York 01", "US", Locale.ENGLISH),
        )
        assertEquals(
            "美国 · 美国节点",
            ServerLocationPolicy.decorate("美国节点", "US", Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals("Node", ServerLocationPolicy.decorate("Node", "ZZ", Locale.ENGLISH))
        assertEquals("Node", ServerLocationPolicy.decorate("Node", null, Locale.ENGLISH))
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
