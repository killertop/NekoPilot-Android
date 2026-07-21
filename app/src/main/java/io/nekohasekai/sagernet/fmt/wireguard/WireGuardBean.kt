package io.nekohasekai.sagernet.fmt.wireguard

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class WireGuardBean : AbstractBean() {
    @JvmField var localAddress: String = ""
    @JvmField var privateKey: String = ""
    @JvmField var peerPublicKey: String = ""
    @JvmField var peerPreSharedKey: String = ""
    @JvmField var mtu: Int = 1420
    @JvmField var reserved: String = ""

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(localAddress)
        output.writeString(privateKey)
        output.writeString(peerPublicKey)
        output.writeString(peerPreSharedKey)
        output.writeInt(mtu)
        output.writeString(reserved)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported WireGuard profile" }
        super.deserialize(input)
        localAddress = input.readString().orEmpty()
        privateKey = input.readString().orEmpty()
        peerPublicKey = input.readString().orEmpty()
        peerPreSharedKey = input.readString().orEmpty()
        mtu = input.readInt()
        reserved = input.readString().orEmpty()
    }

    override fun clone(): WireGuardBean = KryoConverters.deserialize(WireGuardBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 2
        @JvmField val CREATOR = object : CREATOR<WireGuardBean>() {
            override fun newInstance() = WireGuardBean()
            override fun newArray(size: Int): Array<WireGuardBean?> = arrayOfNulls(size)
        }
    }
}
