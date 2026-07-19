package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity

class ProxyInstance(profile: ProxyEntity) : BoxInstance(profile) {

    var displayProfileName = ServiceNotification.genTitle(profile)

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
    }
}
