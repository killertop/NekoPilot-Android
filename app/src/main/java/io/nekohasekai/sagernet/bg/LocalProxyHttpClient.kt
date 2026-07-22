package io.nekohasekai.sagernet.bg

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient

internal fun OkHttpClient.Builder.useActiveVpnProxy(): OkHttpClient.Builder =
    if (!DataStore.serviceState.connected) this else {
        // Prefer the exact immutable endpoint owned by the running service. Binder publishes the
        // snapshot before Connected becomes visible, eliminating both Room-cache lag after a
        // fallback port and first-launch credential races. The database is only a startup fallback.
        val endpoint = DataStore.vpnService?.localProxyEndpoint()
            ?: ActiveLocalProxyEndpoint.snapshot
            ?: DataStore.localProxyEndpoint(refresh = true)
        useLocalMixedProxy(
            enabled = true,
            port = endpoint.port,
            username = endpoint.username,
            password = endpoint.password,
        )
    }

internal object ActiveLocalProxyEndpoint {
    @Volatile
    var snapshot: DataStore.LocalProxyEndpoint? = null
}

private const val ENDPOINT_PORT = "port"
private const val ENDPOINT_USERNAME = "username"
private const val ENDPOINT_PASSWORD = "password"

internal fun DataStore.LocalProxyEndpoint.toBundle(): Bundle = Bundle().apply {
    putInt(ENDPOINT_PORT, port)
    putString(ENDPOINT_USERNAME, username)
    putString(ENDPOINT_PASSWORD, password)
}

internal fun Bundle.toLocalProxyEndpoint(): DataStore.LocalProxyEndpoint? {
    val port = getInt(ENDPOINT_PORT)
    val username = getString(ENDPOINT_USERNAME).orEmpty()
    val password = getString(ENDPOINT_PASSWORD).orEmpty()
    return if (port in 1..65_535 && username.isNotBlank() && password.isNotBlank()) {
        DataStore.LocalProxyEndpoint(port, username, password)
    } else null
}

@Suppress("DEPRECATION")
internal fun activePhysicalNetwork(): Network? = SagerNet.underlyingNetwork
    ?: SagerNet.connectivity.allNetworks.firstOrNull { network ->
        SagerNet.connectivity.getNetworkCapabilities(network)?.let { capabilities ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true
    }

/** Binds a retry to the physical network so it cannot loop back into the app's own TUN. */
internal fun OkHttpClient.Builder.useUnderlyingNetwork(network: Network): OkHttpClient.Builder {
    return socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
        })
}

internal fun OkHttpClient.Builder.useLocalMixedProxy(
    enabled: Boolean,
    port: Int,
    username: String,
    password: String,
): OkHttpClient.Builder {
    if (!enabled) return this
    val authorization = Credentials.basic(username, password)
    return proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        .proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", authorization)
                    .build()
            }
        }
}
