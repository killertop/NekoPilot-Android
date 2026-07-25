package io.nekohasekai.sagernet.fmt.hysteria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaFmtTest {
    @Test
    fun parsesHysteriaV1XplusObfuscation() {
        val profile = parseHysteria(
            "hysteria://example.com:443?upmbps=10&downmbps=50&obfs=xplus&obfsParam=secret#Edge",
        )

        assertEquals(1, profile.protocolVersion)
        assertEquals("secret", profile.obfuscation)
    }

    @Test
    fun parsesStandardHysteria2AuthorityPortHoppingAndGeckoObfuscation() {
        val profile = parseHysteria(
            "hy2://user:pass@example.com:123,5000-6000/?sni=edge.example&obfs=gecko&obfs-password=mask#Edge",
        )

        assertEquals(2, profile.protocolVersion)
        assertEquals("example.com", profile.serverAddress)
        assertEquals(123, profile.serverPort)
        assertEquals("123,5000-6000", profile.serverPorts)
        assertEquals("user:pass", profile.authPayload)
        assertEquals("edge.example", profile.sni)
        assertEquals("mask", profile.obfuscation)
        assertEquals(HysteriaBean.HYSTERIA2_OBFS_GECKO, profile.hysteria2ObfsType)
        assertEquals("Edge", profile.name)
    }

    @Test
    fun rejectsUnsupportedOrLossyHysteriaUriFeatures() {
        listOf(
            "hysteria://example.com:443?protocol=faketcp",
            "hysteria://example.com:443?upmbps=10&downmbps=50&obfs=unsupported&obfsParam=secret",
            "hy2://password@example.com:443/?pinSHA256=deadbeef",
            "hy2://password@example.com:443/?ech=base64-config",
            "hy2://password@example.com:443/?obfs=gecko",
            "hy2://password@example.com:443/?unknown=value",
            "hy2://password@example.com:443,5000/?mport=6000-7000",
        ).forEach { link ->
            val failure = runCatching { parseHysteria(link) }.exceptionOrNull()
            assertTrue("expected $link to be rejected", failure is IllegalArgumentException || failure is IllegalStateException)
        }
    }

    @Test
    fun sharedValidationRejectsManualSettingsUnsupportedByOfficialRuntime() {
        val valid = HysteriaBean().apply {
            protocolVersion = 1
            serverAddress = "edge.example"
            serverPorts = "443,5000-6000"
            protocol = HysteriaBean.PROTOCOL_UDP
            uploadMbps = 10
            downloadMbps = 50
            hopInterval = 10
        }
        validateHysteriaProfile(valid)

        listOf(
            valid.clone().apply { protocol = HysteriaBean.PROTOCOL_FAKETCP },
            valid.clone().apply { uploadMbps = 0 },
            valid.clone().apply { hopInterval = 0 },
        ).forEach { profile ->
            val failure = runCatching { validateHysteriaProfile(profile) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
        }
    }
}
