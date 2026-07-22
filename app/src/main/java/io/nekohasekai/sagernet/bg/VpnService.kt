package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.core.SubscriptionDataCore
import io.nekohasekai.sagernet.fmt.KotlinSingBoxConfigInput
import io.nekohasekai.sagernet.fmt.KotlinSelectorNode
import io.nekohasekai.sagernet.fmt.buildKotlinSingBoxConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.Subnet
import io.nekohasekai.sagernet.utils.sanitizePerAppPackages
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"

    }

    var conn: ParcelFileDescriptor? = null
    private var officialCore: OfficialLibboxController? = null
    private var autoNodeSelector: AutoNodeSelector? = null

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startCore(profile: ProxyEntity) {
        DataStore.vpnService = this
        OfficialLibboxRuntime.ensureSetup(this)
        RuleAssetsUpdater.ensureBundledAssets(this)
        val includePackages = if (DataStore.proxyApps) {
            val selectedPackages = DataStore.individual.lineSequence().map(String::trim).filter(String::isNotEmpty)
                .filter { packageName -> runCatching { packageManager.getApplicationInfo(packageName, 0) }.isSuccess }
                .distinct()
                .toList()
            require(selectedPackages.isNotEmpty()) {
                getString(R.string.app_proxy_empty_selection)
            }
            (selectedPackages + packageName).distinct()
        } else {
            emptyList()
        }
        val selectorProfiles = loadSelectorProfiles(profile)
        val sessionSuffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val selectorTag = "proxy-$sessionSuffix"
        val testGroupTag = "auto-test-$sessionSuffix"
        val config = buildKotlinSingBoxConfig(
            KotlinSingBoxConfigInput(
                selected = profile.requireBean(),
                selectedProfileId = profile.id,
                selectorNodes = selectorProfiles.map {
                    KotlinSelectorNode(it.id, it.requireBean())
                },
                proxyTag = selectorTag,
                testGroupTag = testGroupTag,
                useVpn = true,
                tunStack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                },
                mixedPort = DataStore.mixedPort,
                mixedUsername = DataStore.mixedProxyUsername,
                mixedPassword = DataStore.mixedProxyPassword,
                allowAccess = DataStore.allowAccess,
                ruleAssetDirectory = SagerNet.application.externalAssets.absolutePath,
            ),
        )
        officialCore = OfficialLibboxController(
            platform = OfficialLibboxPlatform(
                this,
                ::openTunFromOfficialLibbox,
                ::protect,
            ),
            onServiceStop = { runOnDefaultDispatcher { stopRunner(false) } },
            onServiceReload = { reload() },
        ).also { it.startOrReload(config, includePackages) }
        if (selectorProfiles.size > 1) {
            val byTag = selectorProfiles.associateBy { AutoNodeSelector.nodeTag(it.id) }
            autoNodeSelector = AutoNodeSelector(
                selectorTag = selectorTag,
                testGroupTag = testGroupTag,
                profilesByTag = byTag,
                initialProfileId = profile.id,
                initiallyEnabled = DataStore.autoSwitch,
                onMeasurements = ::persistAutomaticMeasurements,
                onSelected = ::commitSelection,
                onStatus = ::publishAutomaticSelectionStatus,
                canSelect = ::isSelectorProfileCurrent,
            ).also(AutoNodeSelector::start)
        }
    }

    private fun loadSelectorProfiles(selected: ProxyEntity): List<ProxyEntity> {
        val candidates = SagerDatabase.proxyDao.getLatencyCandidates(
            excludedType = ProxyEntity.TYPE_CONFIG,
            selectedId = selected.id,
            limit = SubscriptionDataCore.MAX_AUTO_SWITCH_CANDIDATES,
        ).map {
            SubscriptionDataCore.AutoSwitchCandidate(it.id, it.status, it.ping)
        }
        val plan = SubscriptionDataCore.planAutoSwitchCandidates(
            candidates = candidates,
            selectedId = selected.id,
            explorationOffset = DataStore.autoSwitchExplorationOffset,
        )
        DataStore.autoSwitchExplorationOffset = plan.nextExplorationOffset
        DataStore.configurationStore.flushBlocking()
        val profiles = SagerDatabase.proxyDao.getEntities(plan.ids).associateBy(ProxyEntity::id)
        return plan.ids.mapNotNull(profiles::get).let { planned ->
            if (planned.any { it.id == selected.id }) planned else listOf(selected) + planned
        }
    }

    private suspend fun persistAutomaticMeasurements(results: Map<Long, Int>) {
        val profiles = SagerDatabase.proxyDao.getEntities(results.keys.toList())
        profiles.forEach { candidate ->
            candidate.status = 1
            candidate.ping = results.getValue(candidate.id)
            candidate.error = null
            candidate.downloadMbps = null
        }
        ProfileManager.updateTestResults(profiles)
    }

    private suspend fun commitSelection(profile: ProxyEntity) {
        withContext(Dispatchers.IO) {
            DataStore.selectedProxy = profile.id
            DataStore.selectedGroup = profile.groupId
            DataStore.currentProfile = profile.id
            DataStore.configurationStore.flushBlocking()
        }
        data.profile = profile
        data.notification?.postNotificationTitle(ServiceNotification.genTitle(profile))
        data.binder.stateChanged(data.state, null)
        sendBroadcast(
            Intent(Action.PROFILES_CHANGED).setPackage(packageName),
            "$packageName.permission.SERVICE_CONTROL",
        )
    }

    private suspend fun publishAutomaticSelectionStatus(status: AutoNodeSelectionStatus?) {
        withContext(Dispatchers.IO) {
            DataStore.autoSwitchStatusProfile = status?.profileId ?: 0L
            DataStore.autoSwitchStatusPhase = status?.phase?.name.orEmpty()
            DataStore.autoSwitchStatusLatency = status?.latencyMs ?: 0
            DataStore.autoSwitchStatusUntil = status?.until ?: 0L
            DataStore.configurationStore.flushBlocking()
        }
        sendBroadcast(
            Intent(Action.AUTO_SWITCH_STATUS_CHANGED).setPackage(packageName),
            "$packageName.permission.SERVICE_CONTROL",
        )
    }

    private suspend fun isSelectorProfileCurrent(snapshot: ProxyEntity): Boolean =
        withContext(Dispatchers.IO) {
            SagerDatabase.proxyDao.getById(snapshot.id)?.let { current ->
                current.type == snapshot.type && current.configRevision == snapshot.configRevision
            } == true
        }

    override fun selectProfile(profileId: Long): Boolean {
        val selector = autoNodeSelector ?: return false
        val current = SagerDatabase.proxyDao.getById(profileId) ?: return false
        // The selector contains an immutable session snapshot. Edited nodes use the normal
        // reload path; unchanged nodes switch in place without disturbing existing streams.
        return selector.selectManually(current)
    }

    override fun setAutomaticNodeSelectionEnabled(enabled: Boolean): Boolean {
        val selector = autoNodeSelector ?: return false
        selector.setEnabled(enabled)
        return true
    }

    override suspend fun stopCore() {
        autoNodeSelector?.close()
        autoNodeSelector = null
        publishAutomaticSelectionStatus(null)
        officialCore?.close()
        officialCore = null
    }

    override fun pauseCore() {
        officialCore?.pause()
    }

    override fun wakeCore() {
        officialCore?.wake()
    }

    override fun resetCoreNetwork() {
        officialCore?.resetNetwork()
    }

    override fun urlTest(): Int {
        check(data.state.connected) { "core not started" }
        val started = SystemClock.elapsedRealtime()
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DataStore.mixedPort)))
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
            .newCall(Request.Builder().url(CONNECTION_TEST_URL).build())
            .execute().use { response -> check(response.isSuccessful) { "HTTP ${response.code}" } }
        return (SystemClock.elapsedRealtime() - started).toInt()
    }

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire() }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override suspend fun killProcesses() {
        runCatching { conn?.close() }.onFailure { error ->
            Logs.w("TUN cleanup failed (${error.javaClass.simpleName})")
        }
        conn = null
        super.killProcesses()
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (prepare(this) != null) {
            startActivity(
                Intent(
                    this, VpnRequestActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    /** Official libbox callback: Android owns the VPN permission, TUN FD and app routing. */
    internal fun openTunFromOfficialLibbox(options: TunOptions): Int {
        check(prepare(this) == null) { "Android VPN permission is required" }
        val builder = Builder()
            .setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(options.mtu.coerceAtLeast(576))

        var hasIpv4 = false
        var hasIpv6 = false
        options.inet4Address.consume { prefix ->
            builder.addAddress(prefix.address(), prefix.prefix())
            hasIpv4 = true
        }
        options.inet6Address.consume { prefix ->
            builder.addAddress(prefix.address(), prefix.prefix())
            hasIpv6 = true
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                options.dnsServerAddress.toList().forEach(builder::addDnsServer)
            }
            var hasIpv4Route = false
            var hasIpv6Route = false
            options.inet4RouteAddress.consume { prefix ->
                builder.addRoute(prefix.address(), prefix.prefix())
                hasIpv4Route = true
            }
            options.inet6RouteAddress.consume { prefix ->
                builder.addRoute(prefix.address(), prefix.prefix())
                hasIpv6Route = true
            }
            if (!hasIpv4Route) options.inet4RouteRange.consume { prefix ->
                builder.addRoute(prefix.address(), prefix.prefix())
                hasIpv4Route = true
            }
            if (!hasIpv6Route) options.inet6RouteRange.consume { prefix ->
                builder.addRoute(prefix.address(), prefix.prefix())
                hasIpv6Route = true
            }
            if (hasIpv4 && !hasIpv4Route) builder.addRoute("0.0.0.0", 0)
            if (hasIpv6 && !hasIpv6Route) builder.addRoute("::", 0)
        }

        val includedPackages = options.includePackage.toList()
        val excludedPackages = options.excludePackage.toList()
        require(includedPackages.isEmpty() || excludedPackages.isEmpty()) {
            "TUN cannot include and exclude packages simultaneously"
        }
        includedPackages.forEach { packageName ->
            runCatching { builder.addAllowedApplication(packageName) }
                .onFailure { Logs.w("Unable to include $packageName in VPN") }
        }
        excludedPackages.forEach { packageName ->
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { Logs.w("Unable to exclude $packageName from VPN") }
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)
        conn?.close()
        conn = builder.establish() ?: throw NullConnectionException()
        return requireNotNull(conn).fd
    }

    private fun io.nekohasekai.libbox.RoutePrefixIterator.consume(action: (io.nekohasekai.libbox.RoutePrefix) -> Unit) {
        while (hasNext()) action(next())
    }

    private fun io.nekohasekai.libbox.StringIterator.toList(): List<String> = buildList {
        while (hasNext()) add(next())
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        val capabilities = SagerNet.underlyingNetwork?.let(SagerNet.connectivity::getNetworkCapabilities)
        metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val networks = SagerNet.underlyingNetwork?.let { arrayOf(it) }
        if (builder != null) {
            networks?.let(builder::setUnderlyingNetworks)
        } else {
            setUnderlyingNetworks(networks)
            autoNodeSelector?.networkChanged()
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}
