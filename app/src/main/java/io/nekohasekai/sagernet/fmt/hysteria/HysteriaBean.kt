package io.nekohasekai.sagernet.fmt.hysteria

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class HysteriaBean : AbstractBean() {
    @JvmField var protocolVersion: Int = 2
    @JvmField var serverPorts: String = "443"
    @JvmField var authPayload: String = ""
    @JvmField var obfuscation: String = ""
    @JvmField var sni: String = ""
    @JvmField var caText: String = ""
    @JvmField var uploadMbps: Int = 0
    @JvmField var downloadMbps: Int = 0
    @JvmField var allowInsecure: Boolean = false
    @JvmField var streamReceiveWindow: Int = 0
    @JvmField var connectionReceiveWindow: Int = 0
    @JvmField var disableMtuDiscovery: Boolean = false
    @JvmField var hopInterval: Int = 10
    @JvmField var alpn: String = ""
    @JvmField var authPayloadType: Int = TYPE_NONE
    @JvmField var protocol: Int = PROTOCOL_UDP

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (protocolVersion == 1) {
            if (uploadMbps <= 0) uploadMbps = 10
            if (downloadMbps <= 0) downloadMbps = 50
        }
        if (hopInterval <= 0) hopInterval = 10
        if (serverPorts.isBlank()) serverPorts = "443"
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeInt(protocolVersion)
        output.writeInt(authPayloadType)
        output.writeString(authPayload)
        output.writeInt(protocol)
        output.writeString(obfuscation)
        output.writeString(sni)
        output.writeString(alpn)
        output.writeInt(uploadMbps)
        output.writeInt(downloadMbps)
        output.writeBoolean(allowInsecure)
        output.writeString(caText)
        output.writeInt(streamReceiveWindow)
        output.writeInt(connectionReceiveWindow)
        output.writeBoolean(disableMtuDiscovery)
        output.writeInt(hopInterval)
        output.writeString(serverPorts)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported Hysteria profile" }
        super.deserialize(input)
        protocolVersion = input.readInt()
        authPayloadType = input.readInt()
        authPayload = input.readString().orEmpty()
        protocol = input.readInt()
        obfuscation = input.readString().orEmpty()
        sni = input.readString().orEmpty()
        alpn = input.readString().orEmpty()
        uploadMbps = input.readInt()
        downloadMbps = input.readInt()
        allowInsecure = input.readBoolean()
        caText = input.readString().orEmpty()
        streamReceiveWindow = input.readInt()
        connectionReceiveWindow = input.readInt()
        disableMtuDiscovery = input.readBoolean()
        hopInterval = input.readInt()
        serverPorts = input.readString().orEmpty()
    }

    override fun clone(): HysteriaBean = KryoConverters.deserialize(HysteriaBean(), KryoConverters.serialize(this))

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_STRING = 1
        const val TYPE_BASE64 = 2
        const val PROTOCOL_UDP = 0
        const val PROTOCOL_FAKETCP = 1
        const val PROTOCOL_WECHAT_VIDEO = 2
        private const val CURRENT_VERSION = 7
        @JvmField val CREATOR = object : CREATOR<HysteriaBean>() {
            override fun newInstance() = HysteriaBean()
            override fun newArray(size: Int): Array<HysteriaBean?> = arrayOfNulls(size)
        }
    }
}
