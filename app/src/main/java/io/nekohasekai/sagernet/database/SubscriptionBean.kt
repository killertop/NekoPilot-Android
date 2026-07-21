package io.nekohasekai.sagernet.database

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.Serializable

class SubscriptionBean : Serializable() {
    @JvmField var type: Int = 0
    @JvmField var link: String = ""
    @JvmField var token: String = ""
    @JvmField var forceResolve: Boolean = false
    @JvmField var deduplication: Boolean = false
    @JvmField var updateWhenConnectedOnly: Boolean = false
    @JvmField var customUserAgent: String = ""
    @JvmField var autoUpdate: Boolean = false
    @JvmField var autoUpdateDelay: Int = 1440
    @JvmField var lastUpdated: Int = 0
    @JvmField var bytesUsed: Long = 0L
    @JvmField var bytesRemaining: Long = 0L
    @JvmField var username: String = ""
    @JvmField var expiryDate: Int = 0
    @JvmField var protocols: MutableList<String> = arrayListOf()
    @JvmField var subscriptionUserinfo: String = ""

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        output.writeInt(type)
        output.writeString(link)
        output.writeBoolean(forceResolve)
        output.writeBoolean(deduplication)
        output.writeBoolean(updateWhenConnectedOnly)
        output.writeString(customUserAgent)
        output.writeBoolean(autoUpdate)
        output.writeInt(autoUpdateDelay)
        output.writeInt(lastUpdated)
        output.writeString(subscriptionUserinfo)
    }

    fun serializeForShare(output: ByteBufferOutput) {
        output.writeInt(CURRENT_SHARE_VERSION)
        output.writeInt(type)
        output.writeString(link)
        output.writeBoolean(forceResolve)
        output.writeBoolean(deduplication)
        output.writeBoolean(updateWhenConnectedOnly)
        output.writeString(customUserAgent)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported subscription data" }
        type = input.readInt()
        link = input.readString().orEmpty()
        forceResolve = input.readBoolean()
        deduplication = input.readBoolean()
        updateWhenConnectedOnly = input.readBoolean()
        customUserAgent = input.readString().orEmpty()
        autoUpdate = input.readBoolean()
        autoUpdateDelay = input.readInt()
        lastUpdated = input.readInt()
        subscriptionUserinfo = input.readString().orEmpty()
    }

    fun deserializeFromShare(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_SHARE_VERSION) { "Unsupported shared subscription" }
        type = input.readInt()
        link = input.readString().orEmpty()
        forceResolve = input.readBoolean()
        deduplication = input.readBoolean()
        updateWhenConnectedOnly = input.readBoolean()
        customUserAgent = input.readString().orEmpty()
    }

    override fun initializeDefaultValues() = Unit

    companion object {
        private const val CURRENT_VERSION = 1
        private const val CURRENT_SHARE_VERSION = 0

        @JvmField
        val CREATOR = object : CREATOR<SubscriptionBean>() {
            override fun newInstance() = SubscriptionBean()
            override fun newArray(size: Int): Array<SubscriptionBean?> = arrayOfNulls(size)
        }
    }
}
