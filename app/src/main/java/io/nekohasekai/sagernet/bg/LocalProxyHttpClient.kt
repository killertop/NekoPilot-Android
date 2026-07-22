package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.DataStore
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Credentials
import okhttp3.OkHttpClient

internal fun OkHttpClient.Builder.useActiveVpnProxy(): OkHttpClient.Builder =
    useLocalMixedProxy(
        enabled = DataStore.serviceState.connected,
        port = DataStore.mixedPort,
        username = DataStore.mixedProxyUsername,
        password = DataStore.mixedProxyPassword,
    )

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
