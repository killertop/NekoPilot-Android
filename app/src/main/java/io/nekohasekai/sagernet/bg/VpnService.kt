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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
        private const val MAX_LOCAL_PORT_BIND_ATTEMPTS = 3
        private const val AUTOMATIC_RECOVERY_PROBE_TIMEOUT_MS = 3_000
        private const val AUTOMATIC_RECOVERY_PRIMARY_URL =
            "https://www.gstatic.com/generate_204"
        private const val AUTOMATIC_RECOVERY_SECONDARY_URL =
            "https://www.cloudflare.com/cdn-cgi/trace"
    }

    var conn: ParcelFileDescriptor? = null
    private var officialCore: OfficialLibboxController? = null
    private var officialPlatform: OfficialLibboxPlatform? = null
    private var autoNodeSelector: AutoNodeSelector? = null
    private var trafficMonitor: RuntimeTrafficMonitor? = null
    private var activeLocalProxyEndpoint: DataStore.LocalProxyEndpoint? = null
    private val automaticStatusMutex = Mutex()

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startCore(profile: ProxyEntity) {
        DataStore.vpnService = this
        OfficialLibboxRuntime.ensureSetup(this)
        RuleAssetsUpdater.ensureBundledAssets(this)
        val persistedEndpoint = DataStore.prepareLocalProxyEndpoint(refresh = true)
        val allowLanAccess = DataStore.allowAccess
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
        val attemptedPorts = linkedSetOf<Int>()
        var mixedPort = resolveAvailableMixedPort(
            persistedEndpoint.port,
            allowLanAccess,
        )
        var bindAttempt = 0
        while (true) {
            bindAttempt++
            if (mixedPort != DataStore.mixedPort) {
                Logs.w("Local proxy port ${DataStore.mixedPort} is unavailable; using $mixedPort")
                DataStore.mixedPort = mixedPort
                DataStore.configurationStore.flush()
            }
            val endpoint = persistedEndpoint.copy(port = mixedPort)
            val config = buildKotlinSingBoxConfig(
                KotlinSingBoxConfigInput(
                    selected = profile.requireBean(),
                    selectedProfileId = profile.id,
                    selectorNodes = selectorProfiles.map {
                        KotlinSelectorNode(it.id, it.requireBean())
                    },
                    proxyTag = selectorTag,
                    useVpn = true,
                    mixedPort = endpoint.port,
                    mixedUsername = endpoint.username,
                    mixedPassword = endpoint.password,
                    allowAccess = allowLanAccess,
                    ruleAssetDirectory = SagerNet.application.externalAssets.absolutePath,
                ),
            )
            val platform = OfficialLibboxPlatform(
                this,
                ::openTunFromOfficialLibbox,
                ::protect,
            )
            officialPlatform = platform
            val controller = OfficialLibboxController(
                platform = platform,
                onServiceStop = { runOnDefaultDispatcher { stopRunner(false) } },
                onServiceReload = { reload() },
            )
            try {
                controller.startOrReload(config, includePackages)
                officialCore = controller
                activeLocalProxyEndpoint = endpoint
                trafficMonitor?.close()
                trafficMonitor = RuntimeTrafficMonitor(
                    sessionProxyTag = selectorTag,
                    currentProfileId = { data.profile?.id ?: DataStore.currentProfile },
                )
                break
            } catch (error: Throwable) {
                runCatching { controller.close() }
                officialPlatform = null
                if (
                    bindAttempt >= MAX_LOCAL_PORT_BIND_ATTEMPTS ||
                    !isAddressAlreadyInUse(error)
                ) {
                    throw error
                }
                runCatching { conn?.close() }
                conn = null
                attemptedPorts += mixedPort
                val replacement = resolveAvailableMixedPort(
                    preferredPort = persistedEndpoint.port,
                    allowLanAccess = allowLanAccess,
                    excludedPorts = attemptedPorts,
                )
                Logs.w(
                    "Local proxy port $mixedPort was claimed during startup; " +
                        "retrying on $replacement",
                )
                mixedPort = replacement
            }
        }
        publishAutomaticSelectionStatus(null)
        if (selectorProfiles.size > 1) {
            val byTag = selectorProfiles.associateBy { AutoNodeSelector.nodeTag(it.id) }
            autoNodeSelector = AutoNodeSelector(
                selectorTag = selectorTag,
                profilesByTag = byTag,
                initialProfileId = profile.id,
                initiallyEnabled = DataStore.autoSwitch,
                initialNetworkIdentity = validatedPhysicalNetworkIdentity(),
                nextCandidate = { currentId, excludedIds ->
                    nextFailoverCandidate(selectorProfiles, currentId, excludedIds)
                },
                currentPathHealthy = ::isCurrentProxyPathHealthy,
                onSelected = ::commitSelection,
                onStatus = ::publishAutomaticSelectionStatus,
                canSelect = ::isSelectorProfileCurrent,
            ).also(AutoNodeSelector::start)
        }
    }

    private suspend fun loadSelectorProfiles(selected: ProxyEntity): List<ProxyEntity> {
        val candidateRows = SagerDatabase.proxyDao.getLatencyCandidates(
            excludedType = ProxyEntity.TYPE_CONFIG,
            selectedId = selected.id,
            limit = SubscriptionDataCore.MAX_FAILOVER_SESSION_CANDIDATES,
        )
        val candidates = SagerDatabase.proxyDao.getEntities(candidateRows.map { it.id })
            .associateBy(ProxyEntity::id)
        return buildList {
            add(selected)
            candidateRows.asSequence()
                .filter { it.id != selected.id }
                .take(SubscriptionDataCore.MAX_FAILOVER_SESSION_CANDIDATES - 1)
                .mapNotNull { candidates[it.id] }
                .forEach(::add)
        }
    }

    private suspend fun nextFailoverCandidate(
        sessionSnapshots: Collection<ProxyEntity>,
        currentId: Long,
        excludedIds: Set<Long>,
    ): ProxyEntity? {
        val snapshots = sessionSnapshots.associateBy(ProxyEntity::id)
        val currentRows = SagerDatabase.proxyDao.getEntities(snapshots.keys.toList())
        val candidateId = SubscriptionDataCore.selectFailoverCandidate(
            currentId = currentId,
            candidates = currentRows.map {
                SubscriptionDataCore.FailoverCandidate(it.id, it.status, it.ping)
            },
            excludedIds = excludedIds,
        ) ?: return null
        val current = currentRows.firstOrNull { it.id == candidateId } ?: return null
        val snapshot = snapshots[candidateId] ?: return null
        return current.takeIf {
            it.groupId == snapshot.groupId && it.type == snapshot.type &&
                it.configRevision == snapshot.configRevision
        }
    }

    private fun validatedPhysicalNetworkIdentity(): Long? {
        val network = SagerNet.underlyingNetwork ?: return null
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return null
        return network.networkHandle.takeIf {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    private suspend fun commitSelection(profile: ProxyEntity) {
        withContext(Dispatchers.IO) {
            DataStore.selectProxy(profile.id, profile.groupId)
            DataStore.currentProfile = profile.id
            DataStore.configurationStore.flush()
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
        automaticStatusMutex.withLock {
            withContext(Dispatchers.IO) {
                DataStore.autoSwitchStatus = status?.encode().orEmpty()
                DataStore.configurationStore.flush()
            }
            sendBroadcast(
                Intent(Action.AUTO_SWITCH_STATUS_CHANGED).setPackage(packageName),
                "$packageName.permission.SERVICE_CONTROL",
            )
        }
    }

    /**
     * Confirms a suspected outage without creating latency results or running on a timer.
     * An unavailable physical network is deliberately treated as inconclusive so an ISP/Wi-Fi
     * outage cannot make NekoPilot rotate through every healthy proxy node.
     */
    private suspend fun isCurrentProxyPathHealthy(): Boolean {
        if (validatedPhysicalNetworkIdentity() == null) return true
        val endpoint = activeLocalProxyEndpoint ?: return true
        // Fixed non-CN destinations are routed through the selector. A user-provided test URL
        // may match the China direct rule-set, so it must not mask a dead proxy path here.
        val urls = listOf(AUTOMATIC_RECOVERY_PRIMARY_URL, AUTOMATIC_RECOVERY_SECONDARY_URL)
        val results = supervisorScope {
            urls.map { url ->
                async(Dispatchers.IO) {
                    runCatching {
                        probeUrlThroughLocalMixedProxy(
                            url = url,
                            port = endpoint.port,
                            username = endpoint.username,
                            password = endpoint.password,
                            timeoutMs = AUTOMATIC_RECOVERY_PROBE_TIMEOUT_MS,
                        )
                    }.isSuccess
                }
            }.awaitAll()
        }
        return results.any { it } || validatedPhysicalNetworkIdentity() == null
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

    override fun setAutomaticNodeSwitchingEnabled(enabled: Boolean): Boolean {
        val selector = autoNodeSelector ?: return false
        selector.setEnabled(enabled)
        return true
    }

    override suspend fun stopCore() {
        trafficMonitor?.close()
        trafficMonitor = null
        autoNodeSelector?.close()
        autoNodeSelector = null
        publishAutomaticSelectionStatus(null)
        officialCore?.close()
        officialCore = null
        officialPlatform = null
        activeLocalProxyEndpoint = null
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
        val endpoint = checkNotNull(activeLocalProxyEndpoint) { "local proxy not started" }
        val started = SystemClock.elapsedRealtime()
        probeUrlThroughLocalMixedProxy(
            url = DataStore.connectionTestURL,
            port = endpoint.port,
            username = endpoint.username,
            password = endpoint.password,
            timeoutMs = 3_000,
        )
        return (SystemClock.elapsedRealtime() - started).toInt()
    }

    override fun localProxyEndpoint(): DataStore.LocalProxyEndpoint? = activeLocalProxyEndpoint

    override fun trafficSnapshot() = trafficMonitor?.snapshot()?.toBundle()

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
            officialPlatform?.updateDefaultInterface(SagerNet.underlyingNetwork)
            autoNodeSelector?.networkChanged(validatedPhysicalNetworkIdentity())
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        trafficMonitor?.close()
        trafficMonitor = null
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}
