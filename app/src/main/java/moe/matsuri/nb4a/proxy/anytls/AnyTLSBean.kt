package moe.matsuri.nb4a.proxy.anytls

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class AnyTLSBean : AbstractBean() {
    @JvmField var password: String = ""
    @JvmField var sni: String = ""
    @JvmField var alpn: String = ""
    @JvmField var certificates: String = ""
    @JvmField var utlsFingerprint: String = ""
    @JvmField var allowInsecure: Boolean = false
    @JvmField var echConfig: String = ""

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(password)
        output.writeString(sni)
        output.writeString(alpn)
        output.writeString(certificates)
        output.writeString(utlsFingerprint)
        output.writeBoolean(allowInsecure)
        output.writeString(echConfig)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported AnyTLS profile" }
        super.deserialize(input)
        password = input.readString().orEmpty()
        sni = input.readString().orEmpty()
        alpn = input.readString().orEmpty()
        certificates = input.readString().orEmpty()
        utlsFingerprint = input.readString().orEmpty()
        allowInsecure = input.readBoolean()
        echConfig = input.readString().orEmpty()
    }

    override fun clone(): AnyTLSBean = KryoConverters.deserialize(AnyTLSBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 0
        @JvmField val CREATOR = object : CREATOR<AnyTLSBean>() {
            override fun newInstance() = AnyTLSBean()
            override fun newArray(size: Int): Array<AnyTLSBean?> = arrayOfNulls(size)
        }
    }
}
