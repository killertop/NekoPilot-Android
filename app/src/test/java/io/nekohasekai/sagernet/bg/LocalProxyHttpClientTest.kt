package io.nekohasekai.sagernet.bg

import java.net.InetSocketAddress
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalProxyHttpClientTest {
    @Test
    fun leavesClientDirectWhenVpnIsNotConnected() {
        val client = OkHttpClient.Builder()
            .useLocalMixedProxy(false, 20_880, "user", "password")
            .build()

        assertNull(client.proxy)
    }

    @Test
    fun configuresAuthenticatedLoopbackProxy() {
        val client = OkHttpClient.Builder()
            .useLocalMixedProxy(true, 20_880, "user", "password")
            .build()
        val address = client.proxy!!.address() as InetSocketAddress
        val request = Request.Builder().url("https://example.com/").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(407)
            .message("Proxy Authentication Required")
            .build()

        val authenticated = client.proxyAuthenticator.authenticate(null, response)

        assertEquals("127.0.0.1", address.hostString)
        assertEquals(20_880, address.port)
        assertEquals("Basic dXNlcjpwYXNzd29yZA==", authenticated?.header("Proxy-Authorization"))
    }
}
