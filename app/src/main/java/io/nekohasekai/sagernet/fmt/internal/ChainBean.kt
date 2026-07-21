package io.nekohasekai.sagernet.fmt.internal

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class ChainBean : AbstractBean() {
    @JvmField var proxies: List<Long> = emptyList()

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(CURRENT_VERSION)
        output.writeInt(proxies.size)
        proxies.forEach(output::writeLong)
    }

    override fun deserialize(input: ByteBufferInput) {
        require(input.readInt() == CURRENT_VERSION) { "Unsupported chain profile" }
        val size = input.readInt()
        require(size in 0..MAX_CHAIN_SIZE) { "Chain is too large" }
        proxies = ArrayList<Long>(size).apply {
            repeat(size) { add(input.readLong()) }
        }
    }

    override fun clone(): ChainBean = KryoConverters.deserialize(ChainBean(), KryoConverters.serialize(this))

    companion object {
        private const val CURRENT_VERSION = 1
        private const val MAX_CHAIN_SIZE = 10_000
        @JvmField val CREATOR = object : CREATOR<ChainBean>() {
            override fun newInstance() = ChainBean()
            override fun newArray(size: Int): Array<ChainBean?> = arrayOfNulls(size)
        }
    }
}
