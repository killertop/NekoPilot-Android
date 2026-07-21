package io.nekohasekai.sagernet.fmt.socks

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class SOCKSBean : AbstractBean() {
    @JvmField var protocol: Int = PROTOCOL_SOCKS5
    @JvmField var sUoT: Boolean = false
    @JvmField var username: String = ""
    @JvmField var password: String = ""

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeInt(protocol)
        output.writeString(username)
        output.writeString(password)
        output.writeBoolean(sUoT)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported SOCKS profile" }
        super.deserialize(input)
        protocol = input.readInt()
        username = input.readString().orEmpty()
        password = input.readString().orEmpty()
        sUoT = input.readBoolean()
    }

    override fun clone(): SOCKSBean = KryoConverters.deserialize(SOCKSBean(), KryoConverters.serialize(this))

    companion object {
        const val PROTOCOL_SOCKS4 = 0
        const val PROTOCOL_SOCKS4A = 1
        const val PROTOCOL_SOCKS5 = 2
        private const val CURRENT_VERSION = 2

        @JvmField val CREATOR = object : CREATOR<SOCKSBean>() {
            override fun newInstance() = SOCKSBean()
            override fun newArray(size: Int): Array<SOCKSBean?> = arrayOfNulls(size)
        }
    }
}
