package io.nekohasekai.sagernet.fmt.http

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean

class HttpBean : StandardV2RayBean() {
    @JvmField var username: String = ""
    @JvmField var password: String = ""

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(username)
        output.writeString(password)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported HTTP profile" }
        super.deserialize(input)
        username = input.readString().orEmpty()
        password = input.readString().orEmpty()
    }

    override fun clone(): HttpBean = KryoConverters.deserialize(HttpBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 0
        @JvmField val CREATOR = object : CREATOR<HttpBean>() {
            override fun newInstance() = HttpBean()
            override fun newArray(size: Int): Array<HttpBean?> = arrayOfNulls(size)
        }
    }
}
