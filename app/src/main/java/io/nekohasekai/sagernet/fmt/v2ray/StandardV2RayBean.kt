package io.nekohasekai.sagernet.fmt.v2ray

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import java.util.Locale

abstract class StandardV2RayBean : AbstractBean() {
    @JvmField var uuid: String = ""
    @JvmField var encryption: String = ""
    @JvmField var type: String = "tcp"
    @JvmField var host: String = ""
    @JvmField var path: String = ""
    @JvmField var security: String = "none"
    @JvmField var sni: String = ""
    @JvmField var alpn: String = ""
    @JvmField var utlsFingerprint: String = ""
    @JvmField var allowInsecure: Boolean = false
    @JvmField var realityPubKey: String = ""
    @JvmField var realityShortId: String = ""
    @JvmField var wsMaxEarlyData: Int = 0
    @JvmField var earlyDataHeaderName: String = ""
    @JvmField var certificates: String = ""
    @JvmField var enableECH: Boolean = false
    @JvmField var echConfig: String = ""
    @JvmField var enableMux: Boolean = false
    @JvmField var muxPadding: Boolean = false
    @JvmField var muxType: Int = 0
    @JvmField var muxConcurrency: Int = 1
    @JvmField var packetEncoding: Int = 0

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        type = when (type.lowercase(Locale.ROOT)) {
            "h2" -> "http"
            "" -> "tcp"
            else -> type.lowercase(Locale.ROOT)
        }
        if (security.isBlank()) security = if (this is TrojanBean) "tls" else "none"
        if (muxConcurrency <= 0) muxConcurrency = 1
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(uuid)
        output.writeString(encryption)
        if (this is VMessBean) output.writeInt(alterId)
        output.writeString(type)
        when (type) {
            "ws" -> {
                output.writeString(host)
                output.writeString(path)
                output.writeInt(wsMaxEarlyData)
                output.writeString(earlyDataHeaderName)
            }
            "http", "httpupgrade" -> {
                output.writeString(host)
                output.writeString(path)
            }
            "grpc" -> output.writeString(path)
        }
        output.writeString(security)
        if (security == "tls") {
            output.writeString(sni)
            output.writeString(alpn)
            output.writeString(certificates)
            output.writeBoolean(allowInsecure)
            output.writeString(utlsFingerprint)
            output.writeString(realityPubKey)
            output.writeString(realityShortId)
        }
        output.writeBoolean(enableECH)
        output.writeString(echConfig)
        output.writeInt(packetEncoding)
        output.writeBoolean(enableMux)
        output.writeBoolean(muxPadding)
        output.writeInt(muxType)
        output.writeInt(muxConcurrency)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported V2Ray profile" }
        super.deserialize(input)
        uuid = input.readString().orEmpty()
        encryption = input.readString().orEmpty()
        if (this is VMessBean) alterId = input.readInt()
        type = input.readString().orEmpty()
        when (type) {
            "ws" -> {
                host = input.readString().orEmpty()
                path = input.readString().orEmpty()
                wsMaxEarlyData = input.readInt()
                earlyDataHeaderName = input.readString().orEmpty()
            }
            "http", "httpupgrade" -> {
                host = input.readString().orEmpty()
                path = input.readString().orEmpty()
            }
            "grpc" -> path = input.readString().orEmpty()
        }
        security = input.readString().orEmpty()
        if (security == "tls") {
            sni = input.readString().orEmpty()
            alpn = input.readString().orEmpty()
            certificates = input.readString().orEmpty()
            allowInsecure = input.readBoolean()
            utlsFingerprint = input.readString().orEmpty()
            realityPubKey = input.readString().orEmpty()
            realityShortId = input.readString().orEmpty()
        }
        enableECH = input.readBoolean()
        echConfig = input.readString().orEmpty()
        packetEncoding = input.readInt()
        enableMux = input.readBoolean()
        muxPadding = input.readBoolean()
        muxType = input.readInt()
        muxConcurrency = input.readInt()
    }

    private companion object {
        const val CURRENT_VERSION = 4
    }
}
