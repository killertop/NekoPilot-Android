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
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.Subnet
import io.nekohasekai.sagernet.utils.sanitizePerAppPackages
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.TunOptions
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

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance
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

    // Complete package visibility is required to construct reliable per-app VPN routes.
    @SuppressLint("QueryPermissionsNeeded")
    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
//        Logs.d(tunOptionsJson)
//        Logs.d(tunPlatformOptionsJson)
//        val tunOptions = JSONObject(tunOptionsJson)

        // address & route & MTU ...... use NB4A GUI config
        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(DEFAULT_TUN_MTU)
        val ipv6Mode = IPv6Mode.ENABLE

        // address
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        if (ipv6Mode != IPv6Mode.DISABLE) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
        }
        builder.addDnsServer(PRIVATE_VLAN4_ROUTER)

        // route
        resources.getStringArray(R.array.bypass_private_route).forEach {
            val subnet = Subnet.fromString(it)!!
            builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
        }
        builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
        builder.addRoute(FAKEDNS_VLAN4_CLIENT, 15)
        // https://issuetracker.google.com/issues/149636790
        if (ipv6Mode != IPv6Mode.DISABLE) {
            builder.addRoute("2000::", 3)
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        // app route
        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        val workaroundSYSTEM = false /* DataStore.tunImplementation == TunImplementation.SYSTEM */
        val needBypassRootUid = workaroundSYSTEM || data.proxy!!.config.needsRootUidBypass

        if (proxyApps || needBypassRootUid) {
            val individual = mutableSetOf<String>()
            val allApps by lazy {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                    when (it.packageName) {
                        packageName -> false
                        "android" -> true
                        else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                    }
                }.map {
                    it.packageName
                }
            }
            if (proxyApps) {
                val requestedPackages = DataStore.individual.lineSequence().toList()
                val installedUids = requestedPackages.mapNotNull { selectedPackage ->
                    runCatching {
                        packageManager.getApplicationInfo(selectedPackage, 0).uid
                    }.getOrNull()?.let { selectedPackage to it }
                }.toMap()
                val installedSelection = sanitizePerAppPackages(requestedPackages, installedUids)
                    .filterTo(linkedSetOf(), installedUids::containsKey)
                require(installedSelection.isNotEmpty()) {
                    getString(R.string.app_proxy_empty_selection)
                }
                individual.addAll(installedSelection)
            } else {
                individual.addAll(allApps)
            }

            val added = mutableListOf<String>()

            individual.apply {
                // Allow Matsuri itself using VPN.
                remove(packageName)
                add(packageName)
            }.forEach {
                try {
                    builder.addAllowedApplication(it)
                    added.add(it)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Logs.w(ex)
                }
            }

            // Package names reveal which apps the user routes through the VPN. Keep diagnostics
            // useful without persisting that private selection in logcat or bug reports.
            Logs.d("Added ${added.size} applications to the VPN allow list")
        }

        conn = builder.establish() ?: throw NullConnectionException()

        return conn!!.fd
    }

    /** Official libbox callback: Android owns the VPN permission, TUN FD and app routing. */
    internal fun openTunFromOfficialLibbox(options: TunOptions): Int {
        check(prepare(this) == null) { "Android VPN permission is required" }
        val builder = Builder()
            .setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(options.mtu.coerceAtLeast(576))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val networks = SagerNet.underlyingNetwork?.let { arrayOf(it) }
            if (builder != null) {
                networks?.let(builder::setUnderlyingNetworks)
            } else {
                setUnderlyingNetworks(networks)
            }
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}
