package io.nekohasekai.sagernet.bg

import android.content.Context
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
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

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = JavaNetworkInterface.getNetworkInterfaces().toList().map { interfaceInfo ->
            LibboxNetworkInterface().apply {
                name = interfaceInfo.name
                index = interfaceInfo.index
                mtu = runCatching { interfaceInfo.mtu }.getOrDefault(0)
                addresses = LibboxStringIterator(
                    interfaceInfo.interfaceAddresses.mapNotNull { address ->
                        address.address?.hostAddress?.let { "$it/${address.networkPrefixLength}" }
                    },
                )
                val capabilities = SagerNet.connectivity.allNetworks.firstNotNullOfOrNull { network ->
                    SagerNet.connectivity.getLinkProperties(network)
                        ?.interfaceName
                        ?.takeIf { it == interfaceInfo.name }
                        ?.let { SagerNet.connectivity.getNetworkCapabilities(network) }
                }
                type = when {
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> Libbox.InterfaceTypeWIFI
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> Libbox.InterfaceTypeCellular
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                dnsServer = LibboxStringIterator(
                    SagerNet.connectivity.allNetworks.firstNotNullOfOrNull { network ->
                        SagerNet.connectivity.getLinkProperties(network)
                            ?.takeIf { it.interfaceName == interfaceInfo.name }
                            ?.dnsServers
                    }.orEmpty().mapNotNull { it.hostAddress },
                )
                metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
            }
        }
        return LibboxNetworkInterfaceIterator(interfaces)
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun readWIFIState(): WIFIState? {
        val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
        val ssid = info.ssid.removePrefix("\"").removeSuffix("\"").takeUnless { it == "<unknown ssid>" }.orEmpty()
        return WIFIState(ssid, info.bssid.orEmpty())
    }

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
