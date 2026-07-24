package io.nekohasekai.sagernet.bg

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.core.ConnectionStateRepository
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal fun OkHttpClient.Builder.useActiveVpnProxy(): OkHttpClient.Builder =
    if (!ConnectionStateRepository.stateOrIdle.connected) this else {
        // Prefer the exact immutable endpoint owned by the running service. Binder publishes the
        // snapshot before Connected becomes visible, eliminating both Room-cache lag after a
        // fallback port and first-launch credential races. The database is only a startup fallback.
        val endpoint = ServiceRuntimeRegistry.vpnService?.localProxyEndpoint()
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
internal fun activePhysicalNetwork(): Network? {
    val current = SagerNet.underlyingNetwork
    if (current?.isPhysicalInternetNetwork() == true) return current
    return SagerNet.connectivity.allNetworks.firstOrNull(Network::isPhysicalInternetNetwork)
}

private fun Network.isPhysicalInternetNetwork(): Boolean =
    SagerNet.connectivity.getNetworkCapabilities(this)?.let { capabilities ->
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    } == true

/**
 * Keeps an auxiliary core off NekoPilot's VPN. This is safe in a separate process too: it reads
 * the current physical network directly from ConnectivityManager when no local VPN snapshot is
 * available.
 */
internal fun bindSocketToPhysicalNetwork(fd: Int): Boolean {
    val network = activePhysicalNetwork() ?: return false
    return runCatching {
        // fromFd duplicates the descriptor. Network.bindSocket acts on the underlying socket,
        // while closing this duplicate leaves the Go runtime's descriptor ownership intact.
        ParcelFileDescriptor.fromFd(fd).use { duplicate ->
            network.bindSocket(duplicate.fileDescriptor)
        }
        true
    }.getOrElse { error ->
        Logs.w("Unable to bind auxiliary core socket to the physical network", error)
        false
    }
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

/**
 * Probes a URL through the local HTTP proxy without weakening Android's global cleartext policy.
 * Android deliberately blocks cleartext URLs in high-level HTTP clients. For an explicit HTTP
 * latency endpoint we send only the proxy-form request over the already-local loopback socket;
 * HTTPS keeps OkHttp's normal TLS validation.
 */
internal fun probeUrlThroughLocalMixedProxy(
    url: String,
    port: Int,
    username: String = "",
    password: String = "",
    timeoutMs: Int,
    httpsClient: OkHttpClient? = null,
) {
    val parsed = url.toHttpUrl()
    if (parsed.isHttps) {
        val ownedClient = httpsClient ?: OkHttpClient.Builder()
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .useLocalMixedProxy(true, port, username, password)
            .build()
        ownedClient.newCall(Request.Builder().url(parsed).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
        }
        return
    }

    Socket().use { socket ->
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        val defaultPort = 80
        val literalHost = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
        val hostHeader = if (parsed.port == defaultPort) literalHost else "$literalHost:${parsed.port}"
        val request = buildString {
            append("GET ").append(parsed).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("User-Agent: NekoPilot-URLTest\r\n")
            if (username.isNotBlank() || password.isNotBlank()) {
                append("Proxy-Authorization: ")
                    .append(Credentials.basic(username, password))
                    .append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(request.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
        val statusLine = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
        check(statusLine?.startsWith("HTTP/") == true) { "Invalid HTTP response" }
        val statusCode = statusLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull()
        check(statusCode != null && statusCode in 200..399) { "HTTP ${statusCode ?: "?"}" }
    }
}
