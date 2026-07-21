package io.nekohasekai.sagernet.fmt

import androidx.room.TypeConverter
import com.esotericsoftware.kryo.KryoException
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
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.byteBuffer
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class KryoConverters {
    companion object {
        private val NULL = byteArrayOf()
        private const val MAX_SERIALIZED_BYTES = 8 * 1024 * 1024

        @JvmStatic
        @TypeConverter
        fun serialize(bean: Serializable?): ByteArray {
            if (bean == null) return NULL
            return serializeIntoBuffer(bean::serializeToBuffer)
        }

        internal fun serializeIdentity(bean: AbstractBean): ByteArray =
            serializeIntoBuffer(bean::serializeIdentityToBuffer)

        private inline fun serializeIntoBuffer(write: (com.esotericsoftware.kryo.io.ByteBufferOutput) -> Unit): ByteArray {
            val stream = ByteArrayOutputStream()
            stream.byteBuffer().use { output ->
                write(output)
                output.flush()
            }
            return stream.toByteArray().also {
                if (it.size > MAX_SERIALIZED_BYTES) throw KryoException("Serialized object is too large")
            }
        }

        @JvmStatic
        fun <T : Serializable> deserialize(bean: T, bytes: ByteArray?): T =
            deserializeStrict(bean, bytes)

        @JvmStatic
        fun <T : Serializable> deserializeStrict(bean: T, bytes: ByteArray?): T {
            if (bytes == null) return bean
            if (bytes.size > MAX_SERIALIZED_BYTES) throw KryoException("Serialized object is too large")
            ByteArrayInputStream(bytes).byteBuffer().use { input ->
                bean.deserializeFromBuffer(input)
                require(input.available() == 0) { "Serialized object has trailing data" }
            }
            bean.initializeDefaultValues()
            return bean
        }

        @JvmStatic @TypeConverter fun socksDeserialize(bytes: ByteArray?): SOCKSBean? = decode(bytes, ::SOCKSBean)
        @JvmStatic @TypeConverter fun httpDeserialize(bytes: ByteArray?): HttpBean? = decode(bytes, ::HttpBean)
        @JvmStatic @TypeConverter fun shadowsocksDeserialize(bytes: ByteArray?): ShadowsocksBean? = decode(bytes, ::ShadowsocksBean)
        @JvmStatic @TypeConverter fun configDeserialize(bytes: ByteArray?): ConfigBean? = decode(bytes, ::ConfigBean)
        @JvmStatic @TypeConverter fun vmessDeserialize(bytes: ByteArray?): VMessBean? = decode(bytes, ::VMessBean)
        @JvmStatic @TypeConverter fun trojanDeserialize(bytes: ByteArray?): TrojanBean? = decode(bytes, ::TrojanBean)
        @JvmStatic @TypeConverter fun trojanGoDeserialize(bytes: ByteArray?): TrojanGoBean? = decode(bytes, ::TrojanGoBean)
        @JvmStatic @TypeConverter fun mieruDeserialize(bytes: ByteArray?): MieruBean? = decode(bytes, ::MieruBean)
        @JvmStatic @TypeConverter fun naiveDeserialize(bytes: ByteArray?): NaiveBean? = decode(bytes, ::NaiveBean)
        @JvmStatic @TypeConverter fun hysteriaDeserialize(bytes: ByteArray?): HysteriaBean? = decode(bytes, ::HysteriaBean)
        @JvmStatic @TypeConverter fun sshDeserialize(bytes: ByteArray?): SSHBean? = decode(bytes, ::SSHBean)
        @JvmStatic @TypeConverter fun wireguardDeserialize(bytes: ByteArray?): WireGuardBean? = decode(bytes, ::WireGuardBean)
        @JvmStatic @TypeConverter fun tuicDeserialize(bytes: ByteArray?): TuicBean? = decode(bytes, ::TuicBean)
        @JvmStatic @TypeConverter fun shadowTLSDeserialize(bytes: ByteArray?): ShadowTLSBean? = decode(bytes, ::ShadowTLSBean)
        @JvmStatic @TypeConverter fun anyTLSDeserialize(bytes: ByteArray?): AnyTLSBean? = decode(bytes, ::AnyTLSBean)
        @JvmStatic @TypeConverter fun chainDeserialize(bytes: ByteArray?): ChainBean? = decode(bytes, ::ChainBean)
        @JvmStatic @TypeConverter fun nekoDeserialize(bytes: ByteArray?): NekoBean? = decode(bytes, ::NekoBean)
        @JvmStatic @TypeConverter fun subscriptionDeserialize(bytes: ByteArray?): SubscriptionBean? = decode(bytes, ::SubscriptionBean)

        private fun <T : Serializable> decode(bytes: ByteArray?, factory: () -> T): T? =
            bytes?.takeIf(ByteArray::isNotEmpty)?.let { deserialize(factory(), it) }
    }
}
