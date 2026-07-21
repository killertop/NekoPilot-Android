package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
import moe.matsuri.nb4a.utils.JavaUtil
import java.util.Arrays

abstract class AbstractBean : Serializable() {

    @JvmField
    var serverAddress: String = "127.0.0.1"

    @JvmField
    var serverPort: Int = 1080

    @JvmField
    var name: String = ""

    @JvmField
    var customOutboundJson: String = ""

    @JvmField
    var customConfigJson: String = ""

    @JvmField
    @Transient
    var finalAddress: String = serverAddress

    @JvmField
    @Transient
    var finalPort: Int = serverPort

    override fun initializeDefaultValues() {
        serverAddress = when {
            serverAddress.isBlank() -> "127.0.0.1"
            serverAddress.startsWith('[') && serverAddress.endsWith(']') ->
                serverAddress.unwrapIPV6Host()
            else -> serverAddress
        }
        finalAddress = serverAddress
        finalPort = serverPort
    }

    final override fun serializeToBuffer(output: ByteBufferOutput) {
        serialize(output)
        output.writeInt(CURRENT_ENVELOPE_VERSION)
        output.writeString(name)
        output.writeString(customOutboundJson)
        output.writeString(customConfigJson)
    }

    final override fun deserializeFromBuffer(input: ByteBufferInput) {
        deserialize(input)
        require(input.readInt() == CURRENT_ENVELOPE_VERSION) { "Unsupported profile envelope" }
        name = input.readString().orEmpty()
        customOutboundJson = input.readString().orEmpty()
        customConfigJson = input.readString().orEmpty()
    }

    open fun serialize(output: ByteBufferOutput) {
        output.writeString(serverAddress)
        output.writeInt(serverPort)
    }

    open fun deserialize(input: ByteBufferInput) {
        serverAddress = input.readString().orEmpty()
        serverPort = input.readInt()
    }

    internal fun serializeIdentityToBuffer(output: ByteBufferOutput) {
        serialize(output)
        output.writeInt(CURRENT_ENVELOPE_VERSION)
        output.writeString(customOutboundJson)
        output.writeString(customConfigJson)
    }

    abstract fun clone(): AbstractBean

    final override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        return Arrays.equals(
            KryoConverters.serializeIdentity(this),
            KryoConverters.serializeIdentity(other as AbstractBean),
        )
    }

    final override fun hashCode(): Int = Arrays.hashCode(KryoConverters.serializeIdentity(this))

    final override fun toString(): String = "${javaClass.simpleName} ${JavaUtil.gson.toJson(this)}"

    private companion object {
        const val CURRENT_ENVELOPE_VERSION = 1
    }
}
