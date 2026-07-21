package moe.matsuri.nb4a.proxy.shadowtls

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean

class ShadowTLSBean : StandardV2RayBean() {
    @JvmField var version: Int = 3
    @JvmField var password: String = ""

    init {
        security = "tls"
    }

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        security = "tls"
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeInt(version)
        output.writeString(password)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported ShadowTLS profile" }
        super.deserialize(input)
        version = input.readInt()
        password = input.readString().orEmpty()
    }

    override fun clone(): ShadowTLSBean = KryoConverters.deserialize(ShadowTLSBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 0
        @JvmField val CREATOR = object : CREATOR<ShadowTLSBean>() {
            override fun newInstance() = ShadowTLSBean()
            override fun newArray(size: Int): Array<ShadowTLSBean?> = arrayOfNulls(size)
        }
    }
}
