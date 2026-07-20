package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity

class ProxyInstance(
    profile: ProxyEntity,
    private val selectorProfiles: List<ProxyEntity> = emptyList(),
) : BoxInstance(profile) {

    var displayProfileName = ServiceNotification.genTitle(profile)

    override fun buildConfig() {
        config = io.nekohasekai.sagernet.fmt.buildConfig(
            profile,
            selectorProfiles = selectorProfiles.takeIf { it.isNotEmpty() },
        )
    }

    fun selectProfile(profileId: Long): Boolean {
        val outbound = config.selectorOutbounds[profileId] ?: return false
        return box.selectOutbound(outbound)
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
    }
}
