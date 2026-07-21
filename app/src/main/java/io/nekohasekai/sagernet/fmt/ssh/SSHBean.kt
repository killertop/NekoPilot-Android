package io.nekohasekai.sagernet.fmt.ssh

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class SSHBean : AbstractBean() {
    @JvmField var username: String = "root"
    @JvmField var authType: Int = AUTH_TYPE_PASSWORD
    @JvmField var password: String = ""
    @JvmField var privateKey: String = ""
    @JvmField var privateKeyPassphrase: String = ""
    @JvmField var publicKey: String = ""

    init {
        serverPort = 22
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(username)
        output.writeInt(authType)
        when (authType) {
            AUTH_TYPE_PASSWORD -> output.writeString(password)
            AUTH_TYPE_PRIVATE_KEY -> {
                output.writeString(privateKey)
                output.writeString(privateKeyPassphrase)
            }
        }
        output.writeString(publicKey)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported SSH profile" }
        super.deserialize(input)
        username = input.readString().orEmpty()
        authType = input.readInt()
        when (authType) {
            AUTH_TYPE_PASSWORD -> password = input.readString().orEmpty()
            AUTH_TYPE_PRIVATE_KEY -> {
                privateKey = input.readString().orEmpty()
                privateKeyPassphrase = input.readString().orEmpty()
            }
        }
        publicKey = input.readString().orEmpty()
    }

    override fun clone(): SSHBean = KryoConverters.deserialize(SSHBean(), KryoConverters.serialize(this))

    companion object {
        const val AUTH_TYPE_NONE = 0
        const val AUTH_TYPE_PASSWORD = 1
        const val AUTH_TYPE_PRIVATE_KEY = 2
        private const val CURRENT_VERSION = 0
        @JvmField val CREATOR = object : CREATOR<SSHBean>() {
            override fun newInstance() = SSHBean()
            override fun newArray(size: Int): Array<SSHBean?> = arrayOfNulls(size)
        }
    }
}
