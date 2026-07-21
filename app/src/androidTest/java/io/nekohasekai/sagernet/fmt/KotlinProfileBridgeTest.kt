package io.nekohasekai.sagernet.fmt

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KotlinProfileBridgeTest {

    @Test
    fun profileLinksPopulateKotlinModelsAndCurrentBinaryStorage() {
        val links = listOf(
            "vless://uuid@example.com:8443?type=grpc&security=reality&sni=server.example&pbk=pub&sid=01#vl",
            "trojan://secret@example.net:443?type=ws&host=cdn.example.net&path=%2Ftr#tr",
            "ss://YWVzLTI1Ni1nY206cGFzcw@1.2.3.4:8388#ss",
            "tuic://uuid:password@tuic.example:443?sni=tuic.example&congestion_control=bbr#tuic",
            "anytls://password@any.example:443?sni=any.example&fp=chrome#any",
        ).joinToString("\n")

        val profiles = parseProfiles(links)
        assertEquals(5, profiles.size)
        assertTrue(profiles[0] is VMessBean)
        assertEquals(-1, (profiles[0] as VMessBean).alterId)
        assertTrue(profiles[1] is TrojanBean)
        assertTrue(profiles[2] is ShadowsocksBean)
        assertTrue(profiles[3] is TuicBean)
        assertTrue(profiles[4] is AnyTLSBean)

        profiles.forEach { profile ->
            val bytes = KryoConverters.serialize(profile)
            val restored = profile.javaClass.getDeclaredConstructor().newInstance() as AbstractBean
            KryoConverters.deserializeStrict(restored, bytes)
            assertArrayEquals(profile.javaClass.name, bytes, KryoConverters.serialize(restored))
            assertEquals(profile.name, restored.name)
        }
    }
}
