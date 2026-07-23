package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.libbox.Libbox
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end guard for the 1.14.0-beta.1 rule-set matching correction.
 *
 * The test deliberately avoids the network and bundled geo data: inline rule sets, a loopback
 * HTTP origin, a hosts resolver, and two loopback UDP resolvers make every route observable. It
 * covers the one-default-rule merge, multi-rule independent condition, and inverted-rule boolean
 * behavior for both route and DNS rules.
 */
@RunWith(AndroidJUnit4::class)
class RuleSetSemanticsTest {
    @Test
    fun beta1RuleSetsDriveRouteAndDnsSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        OfficialLibboxRuntime.ensureSetup(context)
        LoopbackHttpServer().use { http ->
            LoopbackDnsServer().use { selectedDns ->
                LoopbackDnsServer().use { fallbackDns ->
                    val mixedPort = reserveTcpPort()
                    val dnsInboundPort = reserveUdpPort()
                    val controller = OfficialLibboxController(
                        platform = OfficialLibboxPlatform(
                            context = context,
                            openTun = { error("TUN is not available in rule-set semantics test") },
                            protectSocket = { true },
                        ),
                        onServiceStop = {},
                        onServiceReload = {},
                    )
                    try {
                        val config = ruleSetConfig(
                            mixedPort = mixedPort,
                            dnsInboundPort = dnsInboundPort,
                            originPort = http.port,
                            selectedDnsPort = selectedDns.port,
                            fallbackDnsPort = fallbackDns.port,
                        )
                        Libbox.checkConfig(config)
                        controller.startOrReload(config)

                        // One non-inverted default rule merges its destination group with the
                        // outer rule. Either suffix must therefore select the block outbound.
                        assertBlocked(mixedPort, "outer.single.route.test", http.port, http)
                        assertBlocked(mixedPort, "set.single.route.test", http.port, http)
                        assertReachable(mixedPort, "miss.single.route.test", http.port, http)

                        // With multiple headless rules, the rule set becomes an independent
                        // condition: the outer suffix alone must not bypass it.
                        assertBlocked(mixedPort, "a.multi.route.test", http.port, http)
                        assertBlocked(mixedPort, "b.multi.route.test", http.port, http)
                        assertReachable(mixedPort, "outer-only.multi.route.test", http.port, http)

                        // An inverted inner rule is a boolean term, rather than a mergeable
                        // destination branch. The blocked suffix escapes the block route.
                        assertBlocked(mixedPort, "other.dns.invert.test", http.port, http)
                        assertReachable(mixedPort, "blocked.dns.invert.test", http.port, http)

                        // Hijack a real DNS packet into the native DNS module. The selected/fallback
                        // loopback responders prove the same semantics for DNS rules.
                        assertDnsRoute(
                            dnsInboundPort,
                            "outer.dns.single.test",
                            selectedDns,
                            fallbackDns,
                            shouldUseSelected = true,
                        )
                        assertDnsRoute(
                            dnsInboundPort,
                            "set.dns.single.test",
                            selectedDns,
                            fallbackDns,
                            shouldUseSelected = true,
                        )
                        assertDnsRoute(
                            dnsInboundPort,
                            "miss.dns.single.test",
                            selectedDns,
                            fallbackDns,
                            shouldUseSelected = false,
                        )
                        assertDnsRoute(
                            dnsInboundPort,
                            "a.dns.multi.test",
                            selectedDns,
                            fallbackDns,
                            shouldUseSelected = true,
                        )
                        assertDnsRoute(
                            dnsInboundPort,
                            "outer-only.dns.multi.test",
                            selectedDns,
                            fallbackDns,
                            shouldUseSelected = false,
                        )
                        assertDnsRoute(
                            dnsInboundPort,
                            "other.dns.invert.test",
                            selectedDns,
                            fallbackDns,
                            shouldUseSelected = true,
                        )
                        assertDnsRoute(
                            dnsInboundPort,
                            "blocked.dns.invert.test",
                            selectedDns,
                            fallbackDns,
                            shouldUseSelected = false,
                        )
                    } finally {
                        controller.close()
                    }
                }
            }
        }
    }

    private fun assertBlocked(mixedPort: Int, host: String, originPort: Int, origin: LoopbackHttpServer) {
        assertFalse("$host unexpectedly reached the direct outbound", proxyGet(mixedPort, host, originPort))
        assertFalse("$host unexpectedly reached the loopback origin", origin.awaitHost(host, 250))
    }

    private fun assertReachable(mixedPort: Int, host: String, originPort: Int, origin: LoopbackHttpServer) {
        assertTrue("$host did not reach the direct outbound", proxyGet(mixedPort, host, originPort))
        assertTrue("$host was not observed by the loopback origin", origin.awaitHost(host))
    }

    private fun assertDnsRoute(
        dnsInboundPort: Int,
        domain: String,
        selectedDns: LoopbackDnsServer,
        fallbackDns: LoopbackDnsServer,
        shouldUseSelected: Boolean,
    ) {
        assertTrue("DNS query for $domain was not answered", queryDnsInbound(dnsInboundPort, domain))
        val expected = if (shouldUseSelected) selectedDns else fallbackDns
        val unexpected = if (shouldUseSelected) fallbackDns else selectedDns
        assertTrue("$domain did not reach its expected DNS server", expected.awaitQuery(domain))
        assertEquals("$domain leaked to the other DNS server", 0, unexpected.queryCount(domain))
    }

    private fun proxyGet(mixedPort: Int, host: String, targetPort: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.connect(InetSocketAddress(LOOPBACK, mixedPort), SOCKET_TIMEOUT_MS)
            val request = buildString {
                append("GET http://").append(host).append(':').append(targetPort).append("/ HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(targetPort).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            val status = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
            status?.split(' ', limit = 3)?.getOrNull(1)?.toIntOrNull() in 200..399
        }
    }.getOrDefault(false)

    private fun queryDnsInbound(port: Int, domain: String): Boolean = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val query = buildDnsQuery(domain)
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(LOOPBACK), port))
            val response = ByteArray(1_500)
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            packet.length >= DNS_HEADER_LENGTH &&
                response[0] == query[0] && response[1] == query[1] &&
                response[2].toInt() and DNS_RESPONSE_FLAG != 0
        }
    }.getOrDefault(false)

    private fun ruleSetConfig(
        mixedPort: Int,
        dnsInboundPort: Int,
        originPort: Int,
        selectedDnsPort: Int,
        fallbackDnsPort: Int,
    ): String = JSONObject().apply {
        put("log", JSONObject().put("level", "error"))
        put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "mixed")
                put("tag", "mixed-in")
                put("listen", LOOPBACK)
                put("listen_port", mixedPort)
            })
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "dns-in")
                put("listen", LOOPBACK)
                put("listen_port", dnsInboundPort)
                put("network", "udp")
            })
        })
        put("outbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
                put("domain_resolver", "hosts")
            })
            put(JSONObject().put("type", "block").put("tag", "blocked"))
        })
        put("route", JSONObject().apply {
            put("auto_detect_interface", false)
            put("default_domain_resolver", "hosts")
            put("rule_set", JSONArray().apply {
                put(inlineRuleSet("route-single", listOf(defaultRule("set.single.route.test"))))
                put(inlineRuleSet("route-multi", listOf(
                    defaultRule("a.multi.route.test"),
                    defaultRule("b.multi.route.test"),
                )))
                put(inlineRuleSet("route-invert", listOf(defaultRule("blocked.dns.invert.test", invert = true))))
                put(inlineRuleSet("dns-single", listOf(defaultRule("set.dns.single.test"))))
                put(inlineRuleSet("dns-multi", listOf(
                    defaultRule("a.dns.multi.test"),
                    defaultRule("b.dns.multi.test"),
                )))
                put(inlineRuleSet("dns-invert", listOf(defaultRule("blocked.dns.invert.test", invert = true))))
            })
            put("rules", JSONArray().apply {
                put(JSONObject()
                    .put("inbound", JSONArray().put("dns-in"))
                    .put("action", "hijack-dns"))
                put(routeRule(JSONObject()
                    .put("rule_set", JSONArray().put("route-single"))
                    .put("domain_suffix", JSONArray().put("outer.single.route.test")), "blocked"))
                put(routeRule(JSONObject()
                    .put("rule_set", JSONArray().put("route-multi"))
                    .put("domain_suffix", JSONArray().put("multi.route.test"))
                    .put("port", JSONArray().put(originPort)), "blocked"))
                put(routeRule(JSONObject()
                    .put("rule_set", JSONArray().put("route-invert"))
                    .put("domain_suffix", JSONArray().put("dns.invert.test"))
                    .put("port", JSONArray().put(originPort)), "blocked"))
            })
            put("final", "direct")
        })
        put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "hosts")
                    put("tag", "hosts")
                    put("predefined", JSONObject().apply {
                        HTTP_ROUTE_HOSTS.forEach { host -> put(host, LOOPBACK) }
                    })
                })
                put(udpDnsServer("dns-selected", selectedDnsPort))
                put(udpDnsServer("dns-fallback", fallbackDnsPort))
            })
            put("rules", JSONArray().apply {
                put(dnsRule(JSONObject()
                    .put("rule_set", JSONArray().put("dns-single"))
                    .put("domain_suffix", JSONArray().put("outer.dns.single.test")), "dns-selected"))
                put(dnsRule(JSONObject()
                    .put("rule_set", JSONArray().put("dns-multi"))
                    .put("domain_suffix", JSONArray().put("dns.multi.test")), "dns-selected"))
                put(dnsRule(JSONObject()
                    .put("rule_set", JSONArray().put("dns-invert"))
                    .put("domain_suffix", JSONArray().put("dns.invert.test")), "dns-selected"))
            })
            put("final", "dns-fallback")
            put("strategy", "prefer_ipv4")
        })
    }.toString()

    private fun inlineRuleSet(tag: String, rules: List<JSONObject>): JSONObject = JSONObject().apply {
        put("type", "inline")
        put("tag", tag)
        put("rules", JSONArray().apply { rules.forEach(::put) })
    }

    private fun defaultRule(domainSuffix: String, invert: Boolean = false): JSONObject = JSONObject().apply {
        put("domain_suffix", JSONArray().put(domainSuffix))
        if (invert) put("invert", true)
    }

    private fun routeRule(match: JSONObject, outbound: String): JSONObject = match.apply {
        put("action", "route")
        put("outbound", outbound)
    }

    private fun dnsRule(match: JSONObject, server: String): JSONObject = match.apply {
        put("action", "route")
        put("server", server)
    }

    private fun udpDnsServer(tag: String, port: Int): JSONObject = JSONObject().apply {
        put("type", "udp")
        put("tag", tag)
        put("server", LOOPBACK)
        put("server_port", port)
        put("detour", "direct")
    }

    private class LoopbackHttpServer : Closeable {
        private val running = AtomicBoolean(true)
        private val servedHosts = ConcurrentHashMap.newKeySet<String>()
        private val server = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK)).apply {
            soTimeout = SERVER_POLL_TIMEOUT_MS
        }
        private val worker = Thread(::serve, "NekoPilot-rule-set-http")

        val port: Int get() = server.localPort

        init {
            worker.isDaemon = true
            worker.start()
        }

        fun awaitHost(host: String, timeoutMs: Int = ASSERT_TIMEOUT_MS): Boolean = await(timeoutMs) {
            servedHosts.contains(normalizeDomain(host))
        }

        private fun serve() {
            while (running.get()) {
                try {
                    server.accept().use(::handle)
                } catch (_: SocketTimeoutException) {
                    // Periodically check the closed flag.
                } catch (_: SocketException) {
                    if (running.get()) continue
                } catch (_: IOException) {
                    if (running.get()) continue
                }
            }
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            if (reader.readLine() == null) return
            var host = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Host:", ignoreCase = true)) {
                    host = line.substringAfter(':').trim().substringBefore(':')
                }
            }
            if (host.isNotBlank()) servedHosts += normalizeDomain(host)
            val body = "ok"
            val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
            socket.getOutputStream().apply {
                write(response.toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
        }

        override fun close() {
            running.set(false)
            server.close()
            worker.join(SERVER_JOIN_TIMEOUT_MS)
        }
    }

    private class LoopbackDnsServer : Closeable {
        private val running = AtomicBoolean(true)
        private val queries = ConcurrentHashMap<String, AtomicInteger>()
        private val socket = DatagramSocket(0, InetAddress.getByName(LOOPBACK)).apply {
            soTimeout = SERVER_POLL_TIMEOUT_MS
        }
        private val worker = Thread(::serve, "NekoPilot-rule-set-dns")

        val port: Int get() = socket.localPort

        init {
            worker.isDaemon = true
            worker.start()
        }

        fun queryCount(domain: String): Int = queries[normalizeDomain(domain)]?.get() ?: 0

        fun awaitQuery(domain: String, timeoutMs: Int = ASSERT_TIMEOUT_MS): Boolean = await(timeoutMs) {
            queryCount(domain) > 0
        }

        private fun serve() {
            val buffer = ByteArray(1_500)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    parseQueryDomain(query)?.let { domain ->
                        queries.computeIfAbsent(domain) { AtomicInteger() }.incrementAndGet()
                    }
                    buildDnsResponse(query)?.let { response ->
                        socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                    }
                } catch (_: SocketTimeoutException) {
                    // Periodically check the closed flag.
                } catch (_: SocketException) {
                    if (running.get()) continue
                } catch (_: IOException) {
                    if (running.get()) continue
                }
            }
        }

        override fun close() {
            running.set(false)
            socket.close()
            worker.join(SERVER_JOIN_TIMEOUT_MS)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DNS_HEADER_LENGTH = 12
        const val DNS_RESPONSE_FLAG = 0x80
        const val SOCKET_TIMEOUT_MS = 5_000
        const val SERVER_POLL_TIMEOUT_MS = 200
        const val SERVER_JOIN_TIMEOUT_MS = 1_000L
        const val ASSERT_TIMEOUT_MS = 3_000

        val HTTP_ROUTE_HOSTS = listOf(
            "outer.single.route.test",
            "set.single.route.test",
            "miss.single.route.test",
            "a.multi.route.test",
            "b.multi.route.test",
            "outer-only.multi.route.test",
            "other.dns.invert.test",
            "blocked.dns.invert.test",
        )

        fun reserveTcpPort(): Int = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK)).use { it.localPort }

        fun reserveUdpPort(): Int = DatagramSocket(0, InetAddress.getByName(LOOPBACK)).use { it.localPort }

        fun await(timeoutMs: Int, predicate: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (predicate()) return true
                Thread.sleep(20)
            }
            return predicate()
        }

        fun normalizeDomain(value: String): String = value.trim().trimEnd('.').lowercase(Locale.ROOT)

        fun buildDnsQuery(domain: String): ByteArray {
            val labels = normalizeDomain(domain).split('.')
            val nameLength = labels.sumOf { it.length + 1 } + 1
            return ByteArray(DNS_HEADER_LENGTH + nameLength + 4).also { query ->
                query[0] = 0x43
                query[1] = 0x21
                query[2] = 0x01 // recursion desired
                query[5] = 0x01 // one question
                var offset = DNS_HEADER_LENGTH
                labels.forEach { label ->
                    query[offset++] = label.length.toByte()
                    label.toByteArray(StandardCharsets.US_ASCII).copyInto(query, offset)
                    offset += label.length
                }
                query[offset++] = 0
                query[offset++] = 0
                query[offset++] = 1 // A
                query[offset++] = 0
                query[offset] = 1 // IN
            }
        }

        fun parseQueryDomain(query: ByteArray): String? {
            if (query.size < DNS_HEADER_LENGTH + 5) return null
            val labels = mutableListOf<String>()
            var offset = DNS_HEADER_LENGTH
            while (offset < query.size) {
                val length = query[offset].toInt() and 0xff
                if (length == 0) {
                    return if (offset + 5 <= query.size) normalizeDomain(labels.joinToString(".")) else null
                }
                if (length and 0xc0 != 0 || offset + length >= query.size) return null
                offset += 1
                labels += query.copyOfRange(offset, offset + length).toString(StandardCharsets.US_ASCII)
                offset += length
            }
            return null
        }

        fun buildDnsResponse(query: ByteArray): ByteArray? {
            val questionEnd = queryQuestionEnd(query) ?: return null
            val typeOffset = questionEnd - 4
            val queryType = (query[typeOffset].toInt() and 0xff shl 8) or (query[typeOffset + 1].toInt() and 0xff)
            val answer = if (queryType == 1) byteArrayOf(127, 0, 0, 1) else ByteArray(0)
            return ByteArray(questionEnd + if (answer.isEmpty()) 0 else 12 + answer.size).also { response ->
                query.copyInto(response, endIndex = questionEnd)
                response[2] = ((query[2].toInt() and 0x7f) or DNS_RESPONSE_FLAG).toByte()
                response[3] = 0x80.toByte() // recursion available, no error
                if (answer.isNotEmpty()) response[7] = 1 // answer count
                if (answer.isNotEmpty()) {
                    var offset = questionEnd
                    response[offset++] = 0xc0.toByte()
                    response[offset++] = 0x0c
                    response[offset++] = query[typeOffset]
                    response[offset++] = query[typeOffset + 1]
                    response[offset++] = 0
                    response[offset++] = 1 // IN
                    response[offset++] = 0
                    response[offset++] = 0
                    response[offset++] = 0
                    response[offset++] = 60
                    response[offset++] = 0
                    response[offset++] = answer.size.toByte()
                    answer.copyInto(response, offset)
                }
            }
        }

        fun queryQuestionEnd(query: ByteArray): Int? {
            if (query.size < DNS_HEADER_LENGTH + 5) return null
            var offset = DNS_HEADER_LENGTH
            while (offset < query.size) {
                val length = query[offset].toInt() and 0xff
                if (length == 0) return if (offset + 5 <= query.size) offset + 5 else null
                if (length and 0xc0 != 0 || offset + length >= query.size) return null
                offset += length + 1
            }
            return null
        }
    }
}
