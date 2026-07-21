package io.nekohasekai.sagernet.fmt.shadowsocks

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class ShadowsocksBean : AbstractBean() {
    @JvmField var method: String = "aes-256-gcm"
    @JvmField var password: String = ""
    @JvmField var plugin: String = ""
    @JvmField var sUoT: Boolean = false

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (method.isBlank()) method = "aes-256-gcm"
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(method)
        output.writeString(password)
        output.writeString(plugin)
        output.writeBoolean(sUoT)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported Shadowsocks profile" }
        super.deserialize(input)
        method = input.readString().orEmpty()
        password = input.readString().orEmpty()
        plugin = input.readString().orEmpty()
        sUoT = input.readBoolean()
    }

    override fun clone(): ShadowsocksBean = KryoConverters.deserialize(ShadowsocksBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 2
        @JvmField val CREATOR = object : CREATOR<ShadowsocksBean>() {
            override fun newInstance() = ShadowsocksBean()
            override fun newArray(size: Int): Array<ShadowsocksBean?> = arrayOfNulls(size)
        }
    }
}
