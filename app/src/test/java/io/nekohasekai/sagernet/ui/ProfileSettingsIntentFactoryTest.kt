package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileSettingsIntentFactoryTest {
    @Test
    fun everyEditableProfileTypeKeepsItsExistingActivityMapping() {
        val expected = mapOf(
            ProxyEntity.TYPE_SOCKS to SocksSettingsActivity::class.java,
            ProxyEntity.TYPE_HTTP to HttpSettingsActivity::class.java,
            ProxyEntity.TYPE_SS to ShadowsocksSettingsActivity::class.java,
            ProxyEntity.TYPE_VMESS to VMessSettingsActivity::class.java,
            ProxyEntity.TYPE_TROJAN to TrojanSettingsActivity::class.java,
            ProxyEntity.TYPE_TROJAN_GO to TrojanGoSettingsActivity::class.java,
            ProxyEntity.TYPE_MIERU to MieruSettingsActivity::class.java,
            ProxyEntity.TYPE_NAIVE to NaiveSettingsActivity::class.java,
            ProxyEntity.TYPE_HYSTERIA to HysteriaSettingsActivity::class.java,
            ProxyEntity.TYPE_SSH to SSHSettingsActivity::class.java,
            ProxyEntity.TYPE_WG to WireGuardSettingsActivity::class.java,
            ProxyEntity.TYPE_TUIC to TuicSettingsActivity::class.java,
            ProxyEntity.TYPE_SHADOWTLS to ShadowTLSSettingsActivity::class.java,
            ProxyEntity.TYPE_ANYTLS to AnyTLSSettingsActivity::class.java,
            ProxyEntity.TYPE_CHAIN to ChainSettingsActivity::class.java,
            ProxyEntity.TYPE_CONFIG to ConfigSettingActivity::class.java,
        )

        expected.forEach { (type, activityClass) ->
            assertEquals(activityClass, ProfileSettingsIntentFactory.activityClassForType(type))
        }
    }

    @Test
    fun unsupportedProfileTypesRemainRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileSettingsIntentFactory.activityClassForType(ProxyEntity.TYPE_NEKO)
        }
    }
}
