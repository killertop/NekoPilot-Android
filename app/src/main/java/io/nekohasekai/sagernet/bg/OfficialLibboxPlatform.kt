package io.nekohasekai.sagernet.bg

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.system.OsConstants
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.sagernet.SagerNet
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * Android-only implementation of the official libbox platform contract.
 * It deliberately owns system APIs only; product configuration remains in Kotlin and the
 * actual sing-box lifecycle remains in [VpnService].
 */
internal class OfficialLibboxPlatform(
    private val context: Context,
    private val openTun: (TunOptions) -> Int,
    private val protectSocket: (Int) -> Boolean,
) : PlatformInterface {

    private val defaultInterfaceMonitorLock = Any()
    private var defaultInterfaceListener: InterfaceUpdateListener? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(protectSocket(fd)) { "Unable to protect socket from VPN" }
    }

    override fun openTun(options: TunOptions): Int = openTun.invoke(options)

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        val uid = SagerNet.connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        check(uid != Process.INVALID_UID) { "Connection owner not found" }
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty().asList()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(LibboxStringIterator(packages))
        }
    }

    @Suppress("DEPRECATION") // ConnectivityManager has no synchronous replacement for this snapshot.
    override fun getInterfaces(): NetworkInterfaceIterator {
        val javaInterfaces = JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .associateBy(JavaNetworkInterface::getName)
        val interfaces = SagerNet.connectivity.allNetworks.mapNotNull { network ->
            val linkProperties = SagerNet.connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val interfaceName = linkProperties.interfaceName ?: return@mapNotNull null
            val interfaceInfo = javaInterfaces[interfaceName] ?: return@mapNotNull null
            val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            LibboxNetworkInterface().apply {
                name = interfaceInfo.name
                index = interfaceInfo.index
                mtu = runCatching { interfaceInfo.mtu }.getOrDefault(0)
                addresses = LibboxStringIterator(
                    interfaceInfo.interfaceAddresses.mapNotNull { address ->
                        address.address?.let { inetAddress ->
                            val hostAddress = if (inetAddress is Inet6Address) {
                                // Java appends "%interface" to scoped IPv6 addresses. Go's
                                // netip.ParsePrefix rejects zones, so rebuild from raw bytes.
                                Inet6Address.getByAddress(inetAddress.address).hostAddress
                            } else {
                                inetAddress.hostAddress
                            }
                            "$hostAddress/${address.networkPrefixLength}"
                        }
                    },
                )
                type = when {
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> Libbox.InterfaceTypeWIFI
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> Libbox.InterfaceTypeCellular
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                dnsServer = LibboxStringIterator(
                    linkProperties.dnsServers.mapNotNull { it.hostAddress },
                )
                metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
                flags = buildInterfaceFlags(interfaceInfo)
            }
        }.distinctBy(LibboxNetworkInterface::getName)
        return LibboxNetworkInterfaceIterator(interfaces)
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        synchronized(defaultInterfaceMonitorLock) {
            defaultInterfaceListener = listener
        }
        updateDefaultInterface(SagerNet.underlyingNetwork ?: SagerNet.connectivity.activeNetwork)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        synchronized(defaultInterfaceMonitorLock) {
            if (defaultInterfaceListener === listener) defaultInterfaceListener = null
        }
    }

    /** Publishes Android's real upstream network to sing-box's platform monitor. */
    fun updateDefaultInterface(network: Network?) {
        val listener = synchronized(defaultInterfaceMonitorLock) { defaultInterfaceListener } ?: return
        if (network == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val linkProperties = SagerNet.connectivity.getLinkProperties(network) ?: return
        val interfaceName = linkProperties.interfaceName?.takeIf(String::isNotBlank) ?: return
        val interfaceIndex = runCatching {
            JavaNetworkInterface.getByName(interfaceName)?.index
        }.getOrNull()?.takeIf { it > 0 } ?: return
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network)
        val expensive = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val constrained = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED) == false
        listener.updateDefaultInterface(interfaceName, interfaceIndex, expensive, constrained)
    }

    private fun buildInterfaceFlags(networkInterface: JavaNetworkInterface): Int {
        var flags = 0
        if (runCatching { networkInterface.isUp }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        }
        if (runCatching { networkInterface.isLoopback }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_LOOPBACK
        }
        if (runCatching { networkInterface.isPointToPoint }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_POINTOPOINT
        }
        if (runCatching { networkInterface.supportsMulticast() }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_MULTICAST
        }
        return flags
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun localDNSTransport(): LocalDNSTransport? = null
    // Wi-Fi identity is optional and NekoPilot exposes no SSID/BSSID routing. Returning null
    // avoids requesting location/Wi-Fi permissions and prevents OEM SecurityExceptions from
    // crossing gomobile JNI, where a pending Java exception would abort the core process.
    override fun readWIFIState(): WIFIState? = null

    override fun checkPlatformShell() = error("Platform shell is not supported")
    override fun createBridge(options: BridgeOptions): BridgeSession = error("Network bridge is not supported")
    override fun lookupSFTPServer(): String = error("SFTP server is not supported")
    override fun lookupUser(username: String): PlatformUser = error("Platform users are not supported")
    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession = error("Platform shell is not supported")
    override fun readSystemSSHHostKey(): String = error("System SSH host key is not supported")
    override fun tailscaleHostname(): String = ""
    override fun registerMyInterface(name: String) = Unit
    override fun sendNotification(notification: Notification) = Unit
    override fun usePlatformBridge(): Boolean = false
    override fun usePlatformShell(): Boolean = false
}

internal class LibboxStringIterator(values: Collection<String>) : StringIterator {
    private val values = values.toList()
    private var position = 0

    override fun hasNext(): Boolean = position < values.size
    override fun len(): Int = values.size
    override fun next(): String = values[position++]
}

private class LibboxNetworkInterfaceIterator(
    private val values: List<LibboxNetworkInterface>,
) : NetworkInterfaceIterator {
    private var position = 0

    override fun hasNext(): Boolean = position < values.size
    override fun next(): LibboxNetworkInterface = values[position++]
}
