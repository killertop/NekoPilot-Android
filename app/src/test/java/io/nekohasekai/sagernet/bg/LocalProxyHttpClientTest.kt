package io.nekohasekai.sagernet.bg

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun cleartextProbeUsesAbsoluteProxyRequestWithoutGlobalHttpClientPolicy() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<List<String>> {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream()
                            .bufferedReader(StandardCharsets.US_ASCII)
                        val lines = buildList {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                add(line)
                            }
                        }
                        socket.getOutputStream().write(
                            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                                .toByteArray(StandardCharsets.US_ASCII),
                        )
                        lines
                    }
                }

                probeUrlThroughLocalMixedProxy(
                    url = "http://cp.cloudflare.com/",
                    port = server.localPort,
                    username = "tester",
                    password = "secret",
                    timeoutMs = 2_000,
                )

                val lines = received.get(2, TimeUnit.SECONDS)
                assertEquals("GET http://cp.cloudflare.com/ HTTP/1.1", lines.first())
                assertTrue(lines.any { it == "Host: cp.cloudflare.com" })
                assertTrue(lines.any { it.startsWith("Proxy-Authorization: Basic ") })
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun cleartextProbeRejectsAnHttpFailureResponse() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val served = executor.submit {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream()
                            .bufferedReader(StandardCharsets.US_ASCII)
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        socket.getOutputStream().write(
                            "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n"
                                .toByteArray(StandardCharsets.US_ASCII),
                        )
                    }
                }

                val error = runCatching {
                    probeUrlThroughLocalMixedProxy(
                        url = "http://cp.cloudflare.com/",
                        port = server.localPort,
                        timeoutMs = 2_000,
                    )
                }.exceptionOrNull()

                assertTrue(error?.message?.contains("HTTP 503") == true)
                served.get(2, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
