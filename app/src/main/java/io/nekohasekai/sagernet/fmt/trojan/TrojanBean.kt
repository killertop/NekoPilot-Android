package io.nekohasekai.sagernet.fmt.trojan

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean

class TrojanBean : StandardV2RayBean() {
    @JvmField var password: String = ""

    init {
        security = "tls"
    }

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (security.isBlank()) security = "tls"
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(password)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported Trojan profile" }
        super.deserialize(input)
        password = input.readString().orEmpty()
    }

    override fun clone(): TrojanBean = KryoConverters.deserialize(TrojanBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 2
        @JvmField val CREATOR = object : CREATOR<TrojanBean>() {
            override fun newInstance() = TrojanBean()
            override fun newArray(size: Int): Array<TrojanBean?> = arrayOfNulls(size)
        }
    }
}
