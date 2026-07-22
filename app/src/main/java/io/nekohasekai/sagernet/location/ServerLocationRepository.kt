package io.nekohasekai.sagernet.location

import android.net.Network
import android.util.AtomicFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.activePhysicalNetwork
import io.nekohasekai.sagernet.bg.useUnderlyingNetwork
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applicationScope
import io.nekohasekai.sagernet.ktx.parseNumericAddress
import io.nekohasekai.sagernet.ktx.readUtf8Limited
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import io.nekohasekai.sagernet.utils.DefaultNetworkListener

/**
 * Resolves node server addresses to ISO country codes without touching their persisted names.
 *
 * The feature is explicitly opt-in. Only resolved public IP addresses are sent to the lookup
 * service; node names, ports, subscription URLs and credentials never leave the device.
 */
object ServerLocationRepository {

    private const val LOOKUP_URL = "https://api.country.is/"
    private const val CACHE_FILE_NAME = "server-locations-v1.json"
    private const val CACHE_VERSION = 1
    private const val CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
    private const val DNS_FAILURE_RETRY_MS = 6L * 60L * 60L * 1_000L
    private const val NETWORK_FAILURE_RETRY_MS = 15L * 60L * 1_000L
    private const val HTTP_TIMEOUT_SECONDS = 10L
    private const val MAX_CACHE_BYTES = 2 * 1024 * 1024
    private const val MAX_CACHE_ENTRIES = 10_000
    private const val MAX_RESPONSE_BYTES = 128 * 1024
    private const val DNS_BATCH_SIZE = 50
    private const val DNS_CONCURRENCY = 5
    private const val LOOKUP_BATCH_SIZE = 100
    private const val LOOKUP_BATCH_INTERVAL_MS = 125L

    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val _changes = MutableSharedFlow<Set<String>?>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes = _changes.asSharedFlow()

    @Volatile
    private var enabledOverride: Boolean? = null

    @Volatile
    private var cacheLoaded = false

    @Volatile
    private var cache: Map<String, ServerLocationRecord> = emptyMap()

    @Volatile
    private var generation = 0L

    private val networkListenerStarted = AtomicBoolean(false)
    private val forceRetry = AtomicBoolean(false)
    private val activeLookupCall = AtomicReference<Call?>()
    private val cacheMutex = Mutex()

    @Volatile
    private var retryJob: Job? = null

    // Accessed only by the single refresh consumer.
    private val retryAfter = HashMap<String, Long>()

    init {
        applicationScope.launch(Dispatchers.IO) {
            for (ignored in refreshRequests) {
                try {
                    runCatching { refreshAll() }.onFailure { error ->
                        Logs.w("Server location refresh failed: ${error.javaClass.simpleName}")
                    }
                } finally {
                    scheduleNextRetry()
                }
            }
        }
    }

    /** Called from the preference UI before its asynchronous store write completes. */
    fun setEnabled(enabled: Boolean) {
        enabledOverride = enabled
        generation++
        val preferenceGeneration = generation
        _changes.tryEmit(null)
        if (enabled) {
            forceRetry.set(true)
            scheduleRefresh()
        } else {
            retryJob?.cancel()
            retryJob = null
            activeLookupCall.getAndSet(null)?.cancel()
            cache = emptyMap()
            cacheLoaded = true
            applicationScope.launch(Dispatchers.IO) {
                cacheMutex.withLock {
                    if (generation != preferenceGeneration || isEnabled()) return@withLock
                    cache = emptyMap()
                    cacheLoaded = true
                    runCatching { cacheFile().delete() }.onFailure { error ->
                        Logs.w("Unable to clear server location cache: ${error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    /** Coalesces initial load, imports, edits and subscription updates into one database scan. */
    fun scheduleRefresh() {
        if (!isEnabled()) return
        ensureNetworkListener()
        refreshRequests.trySend(Unit)
    }

    fun displayName(
        displayAddress: String,
        originalName: String,
        locale: Locale,
    ): String {
        if (!isEnabled()) return originalName
        val host = ServerLocationPolicy.extractHost(displayAddress) ?: return originalName
        val countryCode = cache[host]?.countryCode
        return ServerLocationPolicy.decorate(originalName, countryCode, locale)
    }

    private fun isEnabled(): Boolean = enabledOverride ?: DataStore.showServerLocation

    private suspend fun refreshAll() {
        DataStore.configurationStore.awaitReady()
        if (!isEnabled()) return
        if (forceRetry.getAndSet(false)) retryAfter.clear()
        ensureCacheLoaded()
        val refreshGeneration = generation
        val now = System.currentTimeMillis()
        val hosts = SagerDatabase.proxyDao.getNodeList()
            .asSequence()
            .mapNotNull { ServerLocationPolicy.extractHost(it.displayAddressCache) }
            .distinct()
            .take(MAX_CACHE_ENTRIES)
            .toList()
        val currentHosts = hosts.toHashSet()
        retryAfter.keys.retainAll(currentHosts)
        val cacheStillCurrent = cacheMutex.withLock {
            if (!canContinue(refreshGeneration)) {
                false
            } else {
                if (cache.keys.any { it !in currentHosts }) {
                    cache = cache.filterKeys(currentHosts::contains)
                    writeCache(cache)
                }
                true
            }
        }
        if (!cacheStillCurrent) return
        val pendingHosts = hosts.filter { host ->
            val record = cache[host]
            (record == null || now - record.updatedAt >= CACHE_TTL_MS) &&
                (retryAfter[host] ?: 0L) <= now
        }
        if (pendingHosts.isEmpty()) return

        val network = activePhysicalNetwork()
        if (network == null) {
            val retryAt = now + NETWORK_FAILURE_RETRY_MS
            pendingHosts.forEach { retryAfter[it] = retryAt }
            return
        }
        val resolvedHosts = LinkedHashMap<String, String>(pendingHosts.size)
        val dnsSemaphore = Semaphore(DNS_CONCURRENCY)
        for (batch in pendingHosts.chunked(DNS_BATCH_SIZE)) {
            if (!canContinue(refreshGeneration)) return
            val resolvedBatch = coroutineScope {
                batch.map { host ->
                    async(Dispatchers.IO) {
                        dnsSemaphore.withPermit {
                            host to if (canContinue(refreshGeneration)) {
                                resolvePublicAddress(host, network)
                            } else {
                                null
                            }
                        }
                    }
                }.map { it.await() }
            }
            resolvedBatch.forEach { (host, address) ->
                if (address == null) retryAfter[host] = now + DNS_FAILURE_RETRY_MS
                else resolvedHosts[host] = address
            }
        }
        if (resolvedHosts.isEmpty() || !canContinue(refreshGeneration)) return

        val hostsByIp = resolvedHosts.entries.groupBy({ it.value }, { it.key })
        val unresolvedIps = hostsByIp.keys.toList()
        val client = createClient(network)
        try {
            val lookupBatches = unresolvedIps.chunked(LOOKUP_BATCH_SIZE)
            var cacheDirty = false
            lookupBatches.forEachIndexed { index, ipBatch ->
                if (!canContinue(refreshGeneration)) return
                val countryByIp = lookupCountries(client, ipBatch, refreshGeneration)
                if (!canContinue(refreshGeneration)) return
                val changedHosts = LinkedHashSet<String>()
                val committed = cacheMutex.withLock {
                    if (!canContinue(refreshGeneration)) {
                        false
                    } else {
                        val updatedCache = cache.toMutableMap()
                        ipBatch.forEach { ip ->
                            val matchingHosts = hostsByIp[ip].orEmpty()
                            val country = countryByIp[canonicalIp(ip)]
                            if (country == null) {
                                matchingHosts.forEach { retryAfter[it] = now + DNS_FAILURE_RETRY_MS }
                            } else {
                                matchingHosts.forEach { host ->
                                    updatedCache[host] = ServerLocationRecord(
                                        host = host,
                                        resolvedIp = ip,
                                        countryCode = country,
                                        updatedAt = now,
                                    )
                                    retryAfter.remove(host)
                                    changedHosts += host
                                }
                            }
                        }
                        cache = trimCache(updatedCache)
                        if (changedHosts.isNotEmpty()) cacheDirty = true
                        if (cacheDirty && (index % 5 == 4 || index == lookupBatches.lastIndex)) {
                            writeCache(cache)
                            cacheDirty = false
                        }
                        true
                    }
                }
                if (!committed) return
                if (changedHosts.isNotEmpty()) {
                    _changes.emit(changedHosts)
                }
                if (index < lookupBatches.lastIndex) {
                    delay(LOOKUP_BATCH_INTERVAL_MS)
                }
            }
            // A slow UI collector may coalesce per-batch events. One final invalidation makes
            // every cached result visible without changing the list order or selection.
            if (canContinue(refreshGeneration)) _changes.emit(null)
        } catch (error: Exception) {
            if (canContinue(refreshGeneration)) {
                val retryAt = System.currentTimeMillis() + NETWORK_FAILURE_RETRY_MS
                resolvedHosts.keys.forEach { retryAfter[it] = retryAt }
            }
            throw error
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun canContinue(refreshGeneration: Long): Boolean =
        isEnabled() && generation == refreshGeneration

    private fun ensureNetworkListener() {
        if (!networkListenerStarted.compareAndSet(false, true)) return
        applicationScope.launch {
            DefaultNetworkListener.start(this@ServerLocationRepository) { network ->
                if (network != null && isEnabled()) {
                    forceRetry.set(true)
                    refreshRequests.trySend(Unit)
                }
            }
        }
    }

    private fun scheduleNextRetry() {
        retryJob?.cancel()
        retryJob = null
        if (!isEnabled()) return
        val retryAt = retryAfter.values.minOrNull() ?: return
        val waitMs = (retryAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
        retryJob = applicationScope.launch(Dispatchers.IO) {
            delay(waitMs)
            refreshRequests.trySend(Unit)
        }
    }

    private fun resolvePublicAddress(host: String, network: Network?): String? = runCatching {
        val addresses = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
        addresses
            .asSequence()
            .filter(ServerLocationPolicy::isPublicAddress)
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .firstOrNull()
            ?.hostAddress
            ?.substringBefore('%')
            ?.let(::canonicalIp)
    }.getOrNull()

    private fun createClient(network: Network?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .apply { if (network != null) useUnderlyingNetwork(network) }
        .build()

    private fun lookupCountries(
        client: OkHttpClient,
        ips: List<String>,
        refreshGeneration: Long,
    ): Map<String, String> {
        val requestJson = JsonArray().apply { ips.forEach(::add) }.toString()
        val request = Request.Builder()
            .url(LOOKUP_URL)
            .post(requestJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val call = client.newCall(request)
        activeLookupCall.set(call)
        if (!canContinue(refreshGeneration)) {
            activeLookupCall.compareAndSet(call, null)
            call.cancel()
            return emptyMap()
        }
        return try {
            call.execute().use { response ->
                check(response.isSuccessful) { "Country lookup returned HTTP ${response.code}" }
                val body = checkNotNull(response.body) { "Country lookup returned an empty body" }
                    .byteStream()
                    .readUtf8Limited(MAX_RESPONSE_BYTES, "Country lookup")
                val requested = ips.mapTo(HashSet(ips.size), ::canonicalIp)
                ServerLocationPolicy.parseCountryResponse(body)
                    .mapKeys { canonicalIp(it.key) }
                    .filterKeys(requested::contains)
            }
        } finally {
            activeLookupCall.compareAndSet(call, null)
        }
    }

    private fun canonicalIp(value: String): String {
        val literal = value.trim().removeSurrounding("[", "]").substringBefore('%')
        return literal.parseNumericAddress()
            ?.hostAddress
            ?.substringBefore('%')
            .orEmpty()
    }

    private suspend fun ensureCacheLoaded() {
        val hasCachedEntries = cacheMutex.withLock {
            if (!cacheLoaded) {
                cache = readCache()
                cacheLoaded = true
            }
            cache.isNotEmpty()
        }
        if (hasCachedEntries) _changes.emit(null)
    }

    private fun cacheFile() = AtomicFile(
        File(SagerNet.application.noBackupFilesDir, CACHE_FILE_NAME),
    )

    private fun readCache(): Map<String, ServerLocationRecord> = runCatching {
        val atomicFile = cacheFile()
        if (!atomicFile.baseFile.isFile) return@runCatching emptyMap()
        val text = atomicFile.openRead().use {
            it.readUtf8Limited(MAX_CACHE_BYTES, "Server location cache")
        }
        val root = JsonParser.parseString(text).asJsonObject
        if (root.get("version")?.asInt != CACHE_VERSION) return@runCatching emptyMap()
        val records = LinkedHashMap<String, ServerLocationRecord>()
        root.getAsJsonArray("entries")?.forEach { element ->
            if (records.size >= MAX_CACHE_ENTRIES || !element.isJsonObject) return@forEach
            val item = element.asJsonObject
            val host = item.get("host")?.asString
                ?.let(ServerLocationPolicy::extractHost)
                ?: return@forEach
            val ip = item.get("ip")?.asString?.takeIf(String::isNotBlank) ?: return@forEach
            val country = item.get("country")?.asString
                ?.uppercase(Locale.ROOT)
                ?.takeIf {
                    ServerLocationPolicy.localizedCountry(it, Locale.ENGLISH) != null
                }
                ?: return@forEach
            val updatedAt = item.get("updatedAt")?.asLong?.takeIf { it > 0L } ?: return@forEach
            records[host] = ServerLocationRecord(host, ip, country, updatedAt)
        }
        records
    }.onFailure {
        Logs.w("Ignoring invalid server location cache: ${it.javaClass.simpleName}")
    }.getOrDefault(emptyMap())

    private fun writeCache(records: Map<String, ServerLocationRecord>) {
        val entries = JsonArray()
        records.values.sortedByDescending(ServerLocationRecord::updatedAt).forEach { record ->
            entries.add(record.toJson())
        }
        val bytes = JsonObject().apply {
            addProperty("version", CACHE_VERSION)
            add("entries", entries)
        }.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_CACHE_BYTES) { "Server location cache is too large" }
        val atomicFile = cacheFile()
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun trimCache(
        records: Map<String, ServerLocationRecord>,
    ): Map<String, ServerLocationRecord> {
        val result = LinkedHashMap<String, ServerLocationRecord>()
        var serializedBytes = CACHE_ENVELOPE_BYTES
        records.values
            .sortedByDescending(ServerLocationRecord::updatedAt)
            .take(MAX_CACHE_ENTRIES)
            .forEach { record ->
                val itemBytes = record.toJson().toString().toByteArray(Charsets.UTF_8).size + 1
                if (serializedBytes + itemBytes > MAX_CACHE_BYTES) return@forEach
                result[record.host] = record
                serializedBytes += itemBytes
            }
        return result
    }

    private fun ServerLocationRecord.toJson() = JsonObject().apply {
        addProperty("host", host)
        addProperty("ip", resolvedIp)
        addProperty("country", countryCode)
        addProperty("updatedAt", updatedAt)
    }

    private const val CACHE_ENVELOPE_BYTES = 32
}
