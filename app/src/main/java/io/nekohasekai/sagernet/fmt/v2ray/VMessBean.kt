package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.KryoConverters

class VMessBean : StandardV2RayBean() {
    @JvmField var alterId: Int = 0

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (encryption.isBlank() && alterId != -1) encryption = "auto"
    }

    override fun clone(): VMessBean = KryoConverters.deserialize(VMessBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField val CREATOR = object : CREATOR<VMessBean>() {
            override fun newInstance() = VMessBean()
            override fun newArray(size: Int): Array<VMessBean?> = arrayOfNulls(size)
        }
    }
}
