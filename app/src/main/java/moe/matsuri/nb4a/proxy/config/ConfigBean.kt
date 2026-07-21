package moe.matsuri.nb4a.proxy.config

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class ConfigBean : AbstractBean() {
    @JvmField var type: Int = 0
    @JvmField var config: String = ""

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeInt(type)
        output.writeString(config)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported custom config profile" }
        super.deserialize(input)
        type = input.readInt()
        config = input.readString().orEmpty()
    }

    override fun clone(): ConfigBean = KryoConverters.deserialize(ConfigBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 0
        @JvmField val CREATOR = object : CREATOR<ConfigBean>() {
            override fun newInstance() = ConfigBean()
            override fun newArray(size: Int): Array<ConfigBean?> = arrayOfNulls(size)
        }
    }
}
