package io.nekohasekai.sagernet.fmt.naive

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class NaiveBean : AbstractBean() {
    @JvmField var proto: String = "https"
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var extraHeaders: String = ""
    @JvmField var sni: String = ""
    @JvmField var certificates: String = ""
    @JvmField var insecureConcurrency: Int = 0
    @JvmField var sUoT: Boolean = false

    init {
        serverPort = 443
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(proto)
        output.writeString(username)
        output.writeString(password)
        output.writeString(extraHeaders)
        output.writeString(certificates)
        output.writeString(sni)
        output.writeInt(insecureConcurrency)
        output.writeBoolean(sUoT)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported Naive profile" }
        super.deserialize(input)
        proto = input.readString().orEmpty()
        username = input.readString().orEmpty()
        password = input.readString().orEmpty()
        extraHeaders = input.readString().orEmpty()
        certificates = input.readString().orEmpty()
        sni = input.readString().orEmpty()
        insecureConcurrency = input.readInt()
        sUoT = input.readBoolean()
    }

    override fun clone(): NaiveBean = KryoConverters.deserialize(NaiveBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 3
        @JvmField val CREATOR = object : CREATOR<NaiveBean>() {
            override fun newInstance() = NaiveBean()
            override fun newArray(size: Int): Array<NaiveBean?> = arrayOfNulls(size)
        }
    }
}
