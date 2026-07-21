package io.nekohasekai.sagernet.fmt.tuic

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class TuicBean : AbstractBean() {
    @JvmField var token: String = ""
    @JvmField var caText: String = ""
    @JvmField var udpRelayMode: String = "native"
    @JvmField var congestionController: String = "cubic"
    @JvmField var alpn: String = ""
    @JvmField var disableSNI: Boolean = false
    @JvmField var reduceRTT: Boolean = false
    @JvmField var mtu: Int = 1400
    @JvmField var sni: String = ""
    @JvmField var fastConnect: Boolean = false
    @JvmField var allowInsecure: Boolean = false
    @JvmField var customJSON: String = ""
    @JvmField var protocolVersion: Int = 5
    @JvmField var uuid: String = ""

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(token)
        output.writeString(caText)
        output.writeString(udpRelayMode)
        output.writeString(congestionController)
        output.writeString(alpn)
        output.writeBoolean(disableSNI)
        output.writeBoolean(reduceRTT)
        output.writeInt(mtu)
        output.writeString(sni)
        output.writeBoolean(fastConnect)
        output.writeBoolean(allowInsecure)
        output.writeString(customJSON)
        output.writeInt(protocolVersion)
        output.writeString(uuid)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported TUIC profile" }
        super.deserialize(input)
        token = input.readString().orEmpty()
        caText = input.readString().orEmpty()
        udpRelayMode = input.readString().orEmpty()
        congestionController = input.readString().orEmpty()
        alpn = input.readString().orEmpty()
        disableSNI = input.readBoolean()
        reduceRTT = input.readBoolean()
        mtu = input.readInt()
        sni = input.readString().orEmpty()
        fastConnect = input.readBoolean()
        allowInsecure = input.readBoolean()
        customJSON = input.readString().orEmpty()
        protocolVersion = input.readInt()
        uuid = input.readString().orEmpty()
    }

    override fun clone(): TuicBean = KryoConverters.deserialize(TuicBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 2
        @JvmField val CREATOR = object : CREATOR<TuicBean>() {
            override fun newInstance() = TuicBean()
            override fun newArray(size: Int): Array<TuicBean?> = arrayOfNulls(size)
        }
    }
}
