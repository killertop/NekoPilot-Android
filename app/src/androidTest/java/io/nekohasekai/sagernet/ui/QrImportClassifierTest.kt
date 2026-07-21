package io.nekohasekai.sagernet.ui

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrImportClassifierTest {
    @Test
    fun recognizesAppAndClashSubscriptionLinksThroughGoCore() {
        val appLink = "sn://subscription?encoded-payload"
        val clashLink = "clash://install-config?url=https%3A%2F%2Fexample.com%2Fsub"

        assertEquals(appLink, scannedSubscriptionLink("  $appLink  "))
        assertEquals(clashLink, scannedSubscriptionLink(clashLink))
    }

    @Test
    fun convertsPlainHttpSubscriptionWithoutLosingItsQuery() {
        val source = "https://example.com/sub?token=a+b&client=android"
        val normalized = scannedSubscriptionLink(source)!!
        val encodedUrl = URI(normalized).rawQuery.substringAfter("url=")

        assertEquals(
            source,
            URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8.name()),
        )
    }

    @Test
    fun leavesNodeAndMultilinePayloadsForProfileParser() {
        assertNull(scannedSubscriptionLink("vless://id@example.com:443"))
        assertNull(scannedSubscriptionLink("clash://unsupported"))
        assertNull(scannedSubscriptionLink("https://example.com/a\nhttps://example.com/b"))
        assertNull(scannedSubscriptionLink("not a QR import link"))
    }
}
