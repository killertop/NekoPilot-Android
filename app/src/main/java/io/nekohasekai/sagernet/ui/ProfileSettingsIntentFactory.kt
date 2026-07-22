package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
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

/** Keeps Android navigation knowledge out of the persisted node model. */
internal object ProfileSettingsIntentFactory {
    fun create(context: Context, profile: ProxyEntity, isSubscription: Boolean): Intent =
        Intent(context, activityClassForType(profile.type)).apply {
            putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, profile.id)
            putExtra(ProfileSettingsActivity.EXTRA_IS_SUBSCRIPTION, isSubscription)
        }

    fun activityClassForType(type: Int): Class<out Activity> = when (type) {
        ProxyEntity.TYPE_SOCKS -> SocksSettingsActivity::class.java
        ProxyEntity.TYPE_HTTP -> HttpSettingsActivity::class.java
        ProxyEntity.TYPE_SS -> ShadowsocksSettingsActivity::class.java
        ProxyEntity.TYPE_VMESS -> VMessSettingsActivity::class.java
        ProxyEntity.TYPE_TROJAN -> TrojanSettingsActivity::class.java
        ProxyEntity.TYPE_TROJAN_GO -> TrojanGoSettingsActivity::class.java
        ProxyEntity.TYPE_MIERU -> MieruSettingsActivity::class.java
        ProxyEntity.TYPE_NAIVE -> NaiveSettingsActivity::class.java
        ProxyEntity.TYPE_HYSTERIA -> HysteriaSettingsActivity::class.java
        ProxyEntity.TYPE_SSH -> SSHSettingsActivity::class.java
        ProxyEntity.TYPE_WG -> WireGuardSettingsActivity::class.java
        ProxyEntity.TYPE_TUIC -> TuicSettingsActivity::class.java
        ProxyEntity.TYPE_SHADOWTLS -> ShadowTLSSettingsActivity::class.java
        ProxyEntity.TYPE_ANYTLS -> AnyTLSSettingsActivity::class.java
        ProxyEntity.TYPE_CHAIN -> ChainSettingsActivity::class.java
        ProxyEntity.TYPE_CONFIG -> ConfigSettingActivity::class.java
        else -> throw IllegalArgumentException()
    }
}
