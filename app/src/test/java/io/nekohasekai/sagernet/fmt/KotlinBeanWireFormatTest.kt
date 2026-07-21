package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Modifier
import java.security.MessageDigest

class KotlinBeanWireFormatTest {

    @Test
    fun allKotlinBeansKeepStableCurrentWireFormatAndRoundTrip() {
        val fixtures = beans().map { bean ->
            populateAllSerializedFields(bean)
            val bytes = KryoConverters.serialize(bean)
            val restored = bean.javaClass.getDeclaredConstructor().newInstance() as Serializable
            KryoConverters.deserializeStrict(restored, bytes)
            assertArrayEquals(bean.javaClass.name, bytes, KryoConverters.serialize(restored))
            "${bean.javaClass.name}:${bytes.toHex()}"
        }.joinToString("\n")

        assertEquals(EXPECTED_CURRENT_WIRE_SHA256, fixtures.sha256())
    }

    @Test
    fun equalityIgnoresDisplayNameWithoutMutatingEitherBean() {
        val first = SOCKSBean().apply {
            name = "first"
            serverAddress = "203.0.113.1"
        }
        val second = first.clone().apply { name = "second" }

        assertNotSame(first, second)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals("first", first.name)
        assertEquals("second", second.name)
    }

    @Test
    fun malformedCurrentDataIsRejectedInsteadOfBecomingLocalhostProfile() {
        val malformed = KryoConverters.serialize(SOCKSBean()).apply { this[0] = 99 }
        assertThrows(IllegalArgumentException::class.java) {
            KryoConverters.deserialize(SOCKSBean(), malformed)
        }

        val trailingData = KryoConverters.serialize(SOCKSBean()) + byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) {
            KryoConverters.deserialize(SOCKSBean(), trailingData)
        }
    }

    private fun beans(): List<Serializable> = listOf(
        SubscriptionBean(),
        SOCKSBean(),
        HttpBean(),
        ShadowsocksBean(),
        VMessBean(),
        TrojanBean(),
        TrojanGoBean(),
        MieruBean(),
        NaiveBean(),
        HysteriaBean(),
        SSHBean(),
        WireGuardBean(),
        TuicBean(),
        ShadowTLSBean(),
        AnyTLSBean(),
        ChainBean(),
        NekoBean(),
        ConfigBean(),
    )

    private fun populateAllSerializedFields(bean: Serializable) {
        bean.initializeDefaultValues()
        generateSequence(bean.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) || Modifier.isTransient(it.modifiers) }
            .forEach { field ->
                field.isAccessible = true
                val stableNumber = field.name.hashCode() and 0x3ff
                val value: Any = when (field.type) {
                    String::class.java -> "v_${field.name}"
                    java.lang.Integer::class.java, Int::class.javaPrimitiveType -> 1000 + stableNumber
                    java.lang.Boolean::class.java, Boolean::class.javaPrimitiveType -> stableNumber % 2 == 0
                    java.lang.Long::class.java, Long::class.javaPrimitiveType -> 100_000L + stableNumber
                    List::class.java -> if (field.name == "proxies") {
                        arrayListOf(101L, 202L)
                    } else {
                        arrayListOf("alpha", "beta")
                    }
                    JSONObject::class.java -> JSONObject().put("fixture", field.name)
                    else -> return@forEach
                }
                field.set(bean, value)
            }
        when (bean) {
            is StandardV2RayBean -> {
                bean.type = "ws"
                bean.security = "tls"
            }
            is TrojanGoBean -> bean.type = "ws"
            is MieruBean -> bean.protocol = "UDP"
            is SSHBean -> bean.authType = SSHBean.AUTH_TYPE_PRIVATE_KEY
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .toHex()

    private companion object {
        const val EXPECTED_CURRENT_WIRE_SHA256 =
            "0f615017a75021cfadb2c998a7d52f62af4f0476393741bc13ce7bc141c8d4e7"
    }
}
