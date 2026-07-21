package io.nekohasekai.sagernet.fmt.mieru

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class MieruBean : AbstractBean() {
    @JvmField var protocol: String = "TCP"
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var mtu: Int = 1400

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(protocol)
        output.writeString(username)
        output.writeString(password)
        if (protocol == "UDP") output.writeInt(mtu)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported Mieru profile" }
        super.deserialize(input)
        protocol = input.readString().orEmpty()
        username = input.readString().orEmpty()
        password = input.readString().orEmpty()
        if (protocol == "UDP") mtu = input.readInt()
    }

    override fun clone(): MieruBean = KryoConverters.deserialize(MieruBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 0
        @JvmField val CREATOR = object : CREATOR<MieruBean>() {
            override fun newInstance() = MieruBean()
            override fun newArray(size: Int): Array<MieruBean?> = arrayOfNulls(size)
        }
    }
}
