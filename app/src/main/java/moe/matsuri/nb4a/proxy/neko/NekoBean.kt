package moe.matsuri.nb4a.proxy.neko

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONObject

class NekoBean : AbstractBean() {
    @JvmField var plgId: String = MISSING_PLUGIN_ID
    @JvmField var protocolId: String = ""
    @JvmField var sharedStorage: JSONObject = JSONObject()

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        super.serialize(output)
        output.writeString(plgId)
        output.writeString(protocolId)
        output.writeString(sharedStorage.toString())
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported plugin profile" }
        super.deserialize(input)
        plgId = input.readString().orEmpty()
        protocolId = input.readString().orEmpty()
        sharedStorage = tryParseJSON(input.readString().orEmpty())
    }

    override fun clone(): NekoBean = KryoConverters.deserialize(NekoBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 0
        private const val MISSING_PLUGIN_ID = "moe.matsuri.plugin.donotexist"

        @JvmStatic
        fun tryParseJSON(input: String): JSONObject = runCatching { JSONObject(input) }
            .getOrElse {
                Logs.e(it)
                JSONObject()
            }

        @JvmField val CREATOR = object : CREATOR<NekoBean>() {
            override fun newInstance() = NekoBean()
            override fun newArray(size: Int): Array<NekoBean?> = arrayOfNulls(size)
        }
    }
}
