package io.nekohasekai.sagernet.fmt.trojan_go

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class TrojanGoBean : AbstractBean() {
    @JvmField var password: String = ""
    @JvmField var sni: String = ""
    @JvmField var type: String = "original"
    @JvmField var host: String = ""
    @JvmField var path: String = ""
    @JvmField var encryption: String = "none"
    @JvmField var plugin: String = ""
    @JvmField var allowInsecure: Boolean = false

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (type.isBlank()) type = "original"
        if (encryption.isBlank()) encryption = "none"
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(password)
        output.writeString(sni)
        output.writeString(type)
        if (type == "ws") {
            output.writeString(host)
            output.writeString(path)
        }
        output.writeString(encryption)
        output.writeString(plugin)
        output.writeBoolean(allowInsecure)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported Trojan-Go profile" }
        super.deserialize(input)
        password = input.readString().orEmpty()
        sni = input.readString().orEmpty()
        type = input.readString().orEmpty()
        if (type == "ws") {
            host = input.readString().orEmpty()
            path = input.readString().orEmpty()
        }
        encryption = input.readString().orEmpty()
        plugin = input.readString().orEmpty()
        allowInsecure = input.readBoolean()
    }

    override fun clone(): TrojanGoBean = KryoConverters.deserialize(TrojanGoBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 1
        @JvmField val CREATOR = object : CREATOR<TrojanGoBean>() {
            override fun newInstance() = TrojanGoBean()
            override fun newArray(size: Int): Array<TrojanGoBean?> = arrayOfNulls(size)
        }
    }
}
