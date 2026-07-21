package io.nekohasekai.sagernet.fmt

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KotlinProfileImportTest {
    @Test
    fun parsesVlessTrojanAndAnyTLSWithoutNativeProfileBridge() {
        val profiles = parseProfilesWithGo(
            """
            vless://11111111-1111-1111-1111-111111111111@example.com:443?security=reality&pbk=public-key&sid=abc&type=grpc&serviceName=grpc#VLESS
            trojan://secret@example.com:443?type=ws&host=cdn.example.com&path=%2Fedge#Trojan
            anytls://password@example.com:8443?sni=cdn.example.com&insecure=1#AnyTLS
            socks5://user:pass@example.com:1080#SOCKS
            https://user:pass@example.com:8443?sni=cdn.example.com#HTTP
            ss://YWVzLTI1Ni1nY206cGFzc0BleGFtcGxlLmNvbTo4Mzg4#SS
            vmess://eyJ2IjoiMiIsInBzIjoiVk1lc3MiLCJhZGQiOiJleGFtcGxlLmNvbSIsInBvcnQiOiI0NDMiLCJpZCI6IjIyMjIyMjIyLTIyMjItMjIyMi0yMjIyLTIyMjIyMjIyMjIyMiIsImFpZCI6IjAiLCJuZXQiOiJ3cyIsImhvc3QiOiJjZG4uZXhhbXBsZS5jb20iLCJwYXRoIjoiL3dzIiwidGxzIjoidGxzIn0=
            hysteria://example.com:443?auth=secret&peer=cdn.example.com&upmbps=20&downmbps=100#HY
            """.trimIndent(),
        )

        val vless = profiles[0] as VMessBean
        assertEquals(-1, vless.alterId)
        assertEquals("11111111-1111-1111-1111-111111111111", vless.uuid)
        assertEquals("grpc", vless.type)
        assertEquals("grpc", vless.path)
        assertEquals("public-key", vless.realityPubKey)

        val trojan = profiles[1] as TrojanBean
        assertEquals("secret", trojan.password)
        assertEquals("ws", trojan.type)
        assertEquals("cdn.example.com", trojan.host)
        assertEquals("/edge", trojan.path)

        val anytls = profiles[2] as AnyTLSBean
        assertEquals("password", anytls.password)
        assertEquals("cdn.example.com", anytls.sni)
        assertTrue(anytls.allowInsecure)

        val socks = profiles[3] as SOCKSBean
        assertEquals(SOCKSBean.PROTOCOL_SOCKS5, socks.protocol)
        assertEquals("user", socks.username)

        val http = profiles[4] as HttpBean
        assertEquals("user", http.username)
        assertEquals("cdn.example.com", http.sni)

        val shadowsocks = profiles[5] as ShadowsocksBean
        assertEquals("aes-256-gcm", shadowsocks.method)
        assertEquals("pass", shadowsocks.password)

        val vmess = profiles[6] as VMessBean
        assertEquals(0, vmess.alterId)
        assertEquals("ws", vmess.type)
        assertEquals("cdn.example.com", vmess.host)

        val hysteria = profiles[7] as HysteriaBean
        assertEquals("secret", hysteria.authPayload)
        assertEquals("cdn.example.com", hysteria.sni)
        assertEquals(100, hysteria.downloadMbps)
    }

    @Test
    fun normalizesDuplicateNamesWithoutCallingNativeProfileBridge() {
        val profiles = parseProfilesWithGo(
            """
            vless://11111111-1111-1111-1111-111111111111@example.com:443#same
            vless://22222222-2222-2222-2222-222222222222@example.com:443#same
            """.trimIndent(),
        )
        val normalized = normalizeProfilesWithGo(profiles, deduplicate = false)
        assertEquals(listOf("same", "same (1)"), normalized.profiles.map { it.name })
    }
}
