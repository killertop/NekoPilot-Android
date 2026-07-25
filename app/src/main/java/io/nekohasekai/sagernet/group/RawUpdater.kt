package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.activePhysicalNetwork
import io.nekohasekai.sagernet.bg.useActiveVpnProxy
import io.nekohasekai.sagernet.bg.useUnderlyingNetwork
import io.nekohasekai.sagernet.core.SubscriptionDataCore
import io.nekohasekai.sagernet.core.ConnectionStateRepository
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.displayNameForUi
import io.nekohasekai.sagernet.fmt.normalizeProfiles
import io.nekohasekai.sagernet.fmt.parseProfileDocument
import io.nekohasekai.sagernet.fmt.parseSubscriptionDocument
import io.nekohasekai.sagernet.ktx.*
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val MAX_SUBSCRIPTION_REDIRECTS = 5

/** Parses only network subscription URLs that can never downgrade to cleartext. */
internal fun validateSubscriptionUrl(raw: String): HttpUrl {
    val url = raw.trim().toHttpUrlOrNull()
        ?: throw SubscriptionUrlException("Invalid subscription URL")
    if (url.scheme != "https" || url.host.isBlank()) {
        throw SubscriptionUrlException("Subscription URLs must use HTTPS")
    }
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
        throw SubscriptionSecurityException("Subscription URLs must not contain credentials")
    }
    if (isNonPublicAddressLiteral(url.host)) {
        throw SubscriptionSecurityException("Subscription URL points to a private or reserved address")
    }
    return url
}

/** Covers literal IPv4/IPv6 ranges that must never be contacted by a subscription updater. */
internal fun isNonPublicAddressLiteral(host: String): Boolean {
    if (!host.contains(':') && !IPV4_LITERAL.matches(host)) return false
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    return isNonPublicAddress(address)
}

private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

internal fun isNonPublicAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) return true
    val bytes = address.address
    if (address is Inet4Address) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 0 && third == 0) ||
            (first == 192 && second == 0 && third == 2) ||
            (first == 192 && second == 88 && third == 99) ||
            (first == 192 && second == 168) ||
            (first == 198 && second in 18..19) ||
            (first == 198 && second == 51 && third == 100) ||
            (first == 203 && second == 0 && third == 113) ||
            first >= 224
    }
    if (address is Inet6Address) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        return first == 0 && second == 0 ||
            first in 0xfc..0xfd ||
            first == 0xfe && second in 0x80..0xbf ||
            first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8
    }
    return false
}

private fun publicSubscriptionDns(network: android.net.Network?): Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = network?.getAllByName(hostname)?.toList() ?: Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty()) throw UnknownHostException(hostname)
        if (addresses.any(::isNonPublicAddress)) {
            throw SubscriptionSecurityException(
                "Subscription host resolves to a private or reserved address",
            )
        }
        return addresses
    }
}

private fun validateSubscriptionDestination(url: HttpUrl, network: android.net.Network?) {
    // A proxy CONNECT request can let the proxy resolve the target itself, so validate the
    // current DNS answer before every hop as well as wrapping direct-connection DNS below.
    val addresses = network?.getAllByName(url.host)?.toList() ?: Dns.SYSTEM.lookup(url.host)
    if (addresses.isEmpty()) throw UnknownHostException(url.host)
    if (addresses.any(::isNonPublicAddress)) {
        throw SubscriptionSecurityException(
            "Subscription host resolves to a private or reserved address",
        )
    }
}

internal class SubscriptionIdentityIndex(
    existingBeansById: Map<Long, AbstractBean>,
    private val fingerprintOf: (String, ByteArray) -> String = ::stableProviderFingerprint,
    private val identitiesEqual: (ByteArray, ByteArray) -> Boolean = ByteArray::contentEquals,
) {
    private data class IdentityClass(
        val key: String,
        val modelClass: String,
        val encoded: ByteArray,
    )

    private val classesByFingerprint = linkedMapOf<String, MutableList<IdentityClass>>()
    private val identityByExistingId = mutableMapOf<Long, String>()

    init {
        existingBeansById.entries.sortedBy { entry -> entry.key }.forEach { (id, bean) ->
            identityByExistingId[id] = register(bean)
        }
    }

    fun identityForExisting(profileId: Long): String =
        identityByExistingId.getValue(profileId)

    fun identityForIncoming(incoming: AbstractBean): String = register(incoming)

    private fun register(bean: AbstractBean): String {
        val modelClass = bean.javaClass.name
        // The provider identity is exactly the protocol payload: display name and local JSON
        // overrides are deliberately outside AbstractBean.serialize(). Encoding it directly
        // avoids cloning every node and then serializing the clone again during large updates.
        val encoded = KryoConverters.serializeProviderIdentity(bean)
        val fingerprint = fingerprintOf(modelClass, encoded)
        val classes = classesByFingerprint.getOrPut(fingerprint, ::arrayListOf)
        classes.firstOrNull { candidate ->
            candidate.modelClass == modelClass && identitiesEqual(candidate.encoded, encoded)
        }?.let { candidate -> return candidate.key }

        return "$fingerprint:${classes.size}".also { key ->
            classes += IdentityClass(key, modelClass, encoded)
        }
    }
}

private fun stableProviderFingerprint(modelClass: String, encoded: ByteArray): String {
    val modelClassBytes = modelClass.toByteArray(StandardCharsets.UTF_8)
    require(modelClass.isNotBlank() && modelClassBytes.size <= 512) {
        "Invalid provider identity type"
    }
    require(encoded.isNotEmpty() && encoded.size <= 8 * 1024 * 1024) {
        "Invalid provider identity data"
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(modelClassBytes + byteArrayOf(0) + encoded)
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}

internal fun preserveLocalOverridesAndDetectConfigChange(
    incoming: AbstractBean,
    existing: AbstractBean,
): Boolean {
    incoming.customOutboundJson = existing.customOutboundJson
    incoming.customConfigJson = existing.customConfigJson
    return existing != incoming
}

internal fun preserveDeletionAfterPartialParse(
    hasNamedSkipped: Boolean,
    hasUnnamedSkipped: Boolean,
): Boolean = hasNamedSkipped || hasUnnamedSkipped

internal fun preserveActiveSubscriptionProfile(
    serviceStarted: Boolean,
    activeProfileId: Long,
    candidateProfileId: Long,
): Boolean = serviceStarted && activeProfileId > 0L && candidateProfileId == activeProfileId

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {
        val source = subscription.sourceConfig()
        var runtime = subscription.runtimeState()
        val link = source.link
        var proxies: List<AbstractBean>
        var skippedProfileNames = emptySet<String>()
        var hasUnnamedSkippedProfile = false
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())?.use {
                it.readUtf8Limited(MAX_PROFILE_IMPORT_BYTES, "Subscription")
            }

            val parsed = contentText?.let(::parseSubscriptionRaw)
                ?: throw SubscriptionNoNodesException(responseBytes = contentText?.length?.toLong() ?: 0L)
            proxies = parsed.profiles
            skippedProfileNames = parsed.skippedNames
            hasUnnamedSkippedProfile = parsed.hasUnnamedSkipped
        } else {
            val subscriptionUrl = validateSubscriptionUrl(source.link)

            val request = Request.Builder()
                .url(subscriptionUrl)
                .header("User-Agent", source.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)
                .build()
            val downloaded = downloadSubscriptionWithFallback(request)
            val parsed = parseSubscriptionRaw(downloaded.content)
                ?: throw SubscriptionNoNodesException(
                    contentType = downloaded.contentType,
                    responseBytes = downloaded.responseBytes,
                )
            proxies = parsed.profiles
            skippedProfileNames = parsed.skippedNames
            hasUnnamedSkippedProfile = parsed.hasUnnamedSkipped

            runtime = runtime.copy(
                userInfo = SubscriptionMetadata.sanitizeUserInfo(downloaded.subscriptionUserInfo),
            )

            // 修改默认名字
            val fallbackHost = subscriptionUrl.host
            if (
                proxyGroup.name?.startsWith("Subscription #") == true ||
                (!fallbackHost.isNullOrBlank() && proxyGroup.name == fallbackHost)
            ) {
                SubscriptionMetadata.displayName(downloaded.contentDisposition)
                    ?.let { remoteName -> proxyGroup.name = remoteName }
            }
        }

        // Keep every server supplied by the subscription. De-duplication can silently
        // discard valid endpoints that share an address or protocol shape.
        val normalized = normalizeProfiles(proxies, false)
        proxies = normalized.profiles

        require(proxies.size <= SubscriptionDataCore.MAX_SUBSCRIPTION_PROFILES) {
            app.getString(
                R.string.subscription_too_many_nodes,
                SubscriptionDataCore.MAX_SUBSCRIPTION_PROFILES,
            )
        }
        val existingCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id)
        require(existingCount <= SubscriptionDataCore.MAX_SUBSCRIPTION_PROFILES) {
            app.getString(
                R.string.subscription_too_many_nodes,
                SubscriptionDataCore.MAX_SUBSCRIPTION_PROFILES,
            )
        }

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)
        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        // Capture both selection rows in one SQLite transaction. A later fallback is allowed only
        // if this exact revision is still current after the network request and database update.
        val selectionBefore = DataStore.readProxySelection()
        val selectedBeforeId = selectionBefore.profileId
        val activeBeforeId = DataStore.currentProfile
        val duplicate = emptyList<String>()
        val partialParse = skippedProfileNames.isNotEmpty() || hasUnnamedSkippedProfile

        Logs.d("New profiles: ${proxies.size}")

        // Kotlin owns deterministic identity/name matching and the resulting diff plan.
        // Exact protocol-bean equality remains part of the persisted ABI.
        val existingById = exists.associateBy(ProxyEntity::id)
        val existingBeansById = exists.associate { entity -> entity.id to entity.requireBean() }
        val identityIndex = SubscriptionIdentityIndex(existingBeansById)
        val updatePlan = SubscriptionDataCore.planSubscriptionUpdate(
            incoming = proxies.map { bean ->
                val name = bean.displayNameForUi()
                // AbstractBean equality intentionally ignores display names. The registry also
                // excludes local JSON overrides and collapses identical fingerprints into one
                // verified identity class, avoiding quadratic duplicate-node comparisons.
                SubscriptionDataCore.SubscriptionIncoming(name, identityIndex.identityForIncoming(bean))
            },
            existing = exists.map { entity ->
                SubscriptionDataCore.SubscriptionExisting(
                    id = entity.id,
                    name = entity.displayName(),
                    userOrder = entity.userOrder,
                    identity = identityIndex.identityForExisting(entity.id),
                )
            },
        )
        require(updatePlan.actions.size == proxies.size) { "Subscription update plan is incomplete" }

        val toUpdate = ArrayList<ProxyEntity>()
        val toAdd = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        var nextPartialOrder = (exists.maxOfOrNull(ProxyEntity::userOrder) ?: 0L) + 1L
        // The deterministic plan owns the target order for every incoming profile.
        var changed = 0
        for (action in updatePlan.actions) {
            require(action.incomingIndex in proxies.indices) { "Subscription update plan has an invalid profile" }
            val bean = proxies[action.incomingIndex]
            val name = bean.displayNameForUi()
            val entity = action.existingId?.let(existingById::get)
            if (entity != null) {
                val existsBean = existingBeansById.getValue(entity.id)
                val oldName = entity.displayName()
                // 更新订阅，保留自定义覆写设置
                val configChanged = preserveLocalOverridesAndDetectConfigChange(bean, existsBean)
                when (action.action) {
                    SubscriptionDataCore.SubscriptionActionKind.UPDATE -> {
                        changed++
                        entity.putBean(bean)
                        if (!partialParse) entity.userOrder = action.userOrder
                        if (configChanged) {
                            // Endpoint/auth changes invalidate the old latency and availability;
                            // a display-name-only update keeps both the measurement and connection.
                            entity.clearNodeTestOutcome()
                        }
                        toUpdate.add(entity)
                        updated[oldName] = name
                    }

                    SubscriptionDataCore.SubscriptionActionKind.REORDER -> {
                        if (!partialParse) {
                            toUpdate.add(entity)
                            entity.userOrder = action.userOrder
                        }

                    }

                    SubscriptionDataCore.SubscriptionActionKind.UNCHANGED -> require(
                        partialParse || entity.userOrder == action.userOrder
                    ) {
                        "Subscription update plan marked a changed profile as unchanged"
                    }

                    SubscriptionDataCore.SubscriptionActionKind.ADD -> error(
                        "Subscription update plan mismatched an added profile"
                    )
                }
            } else {
                require(action.action == SubscriptionDataCore.SubscriptionActionKind.ADD) {
                    "Subscription update plan refers to an unknown profile"
                }
                changed++
                toAdd.add(
                    ProxyEntity(
                        groupId = proxyGroup.id,
                        userOrder = if (partialParse) nextPartialOrder++ else action.userOrder,
                    ).apply {
                        putBean(bean)
                    })
                added.add(name)
            }
        }

        val preservedFromPartialParse = ArrayList<ProxyEntity>()
        val toDelete = ArrayList<ProxyEntity>().apply {
            updatePlan.deletionIds.forEach { profileId ->
                val entity = existingById[profileId]
                    ?: error("Subscription update plan deletes an unknown profile")
                if (preserveDeletionAfterPartialParse(
                    hasNamedSkipped = skippedProfileNames.isNotEmpty(),
                    hasUnnamedSkipped = hasUnnamedSkippedProfile,
                )) {
                    preservedFromPartialParse += entity
                } else {
                    add(entity)
                }
            }
        }
        // A provider may remove the currently active node while its old immutable libbox
        // snapshot is still carrying live connections. Keep that database row until a later
        // update performed while disconnected; otherwise Home would point at a fallback node
        // even though the running VPN was still using the removed one. Configuration changes
        // are persisted immediately, but are intentionally applied only after a manual switch
        // or reconnect so an ordinary subscription refresh never tears down established flows.
        val preservedActiveConnection = toDelete.firstOrNull { candidate ->
            preserveActiveSubscriptionProfile(
                serviceStarted = ConnectionStateRepository.stateOrIdle.started,
                activeProfileId = activeBeforeId,
                candidateProfileId = candidate.id,
            )
        }
        if (preservedActiveConnection != null) toDelete.remove(preservedActiveConnection)
        val deleted = toDelete.map { it.displayName() }
        changed += toDelete.size

        if (preservedFromPartialParse.isNotEmpty()) {
            Logs.w("Preserved ${preservedFromPartialParse.size} profiles after a partial subscription parse")
        }
        if (preservedActiveConnection != null) {
            Logs.d("Deferred deletion of the active subscription profile")
        }

        SagerDatabase.proxyDao.applySubscriptionChanges(toAdd, toUpdate, toDelete)
        Logs.d("Added profiles: ${toAdd.size}")
        Logs.d("Updated profiles: ${toUpdate.size}")
        Logs.d("Deleted profiles: ${toDelete.size}")

        val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

        val expectedExistCount = proxies.size + preservedFromPartialParse.size +
            if (preservedActiveConnection == null) 0 else 1
        if (existCount != expectedExistCount) {
            Logs.e("Exist profiles: $existCount, expected profiles: $expectedExistCount")
        }

        // Only fill an empty or stale selection. Updating a different subscription must
        // never silently switch the user's chosen node.
        val selectionAtCommit = DataStore.readProxySelection()
        val selectedProfile = SagerDatabase.proxyDao.getById(selectionAtCommit.profileId)
        val selectionRecovered = if (selectionAtCommit.mayRecoverFrom(
                expected = selectionBefore,
                selectedProfileExists = selectedProfile != null,
            )
        ) {
            // A newly imported source should select one of its own nodes. Falling back to the
            // global list first could leave the user connected to an unrelated old source even
            // though this import was initiated from the empty/unselected state.
            val fallback = SagerDatabase.proxyDao.getNodeListByGroup(proxyGroup.id).firstOrNull()
                ?: SagerDatabase.proxyDao.getNodeList().firstOrNull()
            DataStore.compareAndSetProxySelection(
                selectionAtCommit,
                ProxySelection(fallback?.id ?: 0L, fallback?.groupId ?: 0L),
            )
        } else false

        val selectedAfterId = DataStore.readProxySelection().profileId
        // Periodic updates run in :bg, where main-process listeners do not exist. Notify a
        // currently visible Home screen explicitly; a later Home creation reads Room directly.
        if (userInterface == null && (changed > 0 || selectedBeforeId != selectedAfterId)) {
            app.sendBroadcast(
                Intent(Action.PROFILES_CHANGED).setPackage(app.packageName),
                "${app.packageName}.permission.SERVICE_CONTROL",
            )
        }

        subscription.applyRuntimeState(
            runtime.copy(lastUpdatedSeconds = (System.currentTimeMillis() / 1000).toInt()),
        )
        SagerDatabase.groupDao.updateGroup(proxyGroup)
        SubscriptionDiagnosticsStore.clear(proxyGroup.id)

        runCatching {
            userInterface?.onUpdateSuccess(
                proxyGroup, changed, added, updated, deleted, duplicate, byUser
            )
        }.onFailure {
            Logs.w("Subscription success UI failed (${it.javaClass.simpleName})")
        }
    }


    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? {
        require(text.length <= MAX_PROFILE_IMPORT_BYTES) { "Profile input is too large" }
        val profiles = parseProfileDocument(text).takeIf { it.isNotEmpty() } ?: return null
        if (fileName.isNotBlank() && profiles.size == 1 && profiles[0].name.isBlank()) {
            profiles[0].name = fileName.substringBeforeLast('.')
        }
        return profiles
    }

    private fun parseSubscriptionRaw(text: String) =
        parseSubscriptionDocument(text).takeIf { it.profiles.isNotEmpty() }

    private data class DownloadedSubscription(
        val content: String,
        val subscriptionUserInfo: String,
        val contentDisposition: String,
        val contentType: String,
        val responseBytes: Long,
    )

    private fun downloadSubscriptionWithFallback(request: Request): DownloadedSubscription {
        val connected = ConnectionStateRepository.stateOrIdle.connected
        return try {
            downloadSubscription(request, viaActiveProxy = connected)
        } catch (error: IOException) {
            // A node can reset a specific provider even while ordinary URL tests pass. Retry once
            // on Android's captured physical network; binding the socket avoids a TUN loop.
            val physicalNetwork = activePhysicalNetwork()
            if (!connected || physicalNetwork == null) {
                writeDebugDownloadFailure("primary", error)
                throw error
            }
            try {
                downloadSubscription(
                    request,
                    viaActiveProxy = false,
                    underlyingNetwork = physicalNetwork,
                )
            } catch (fallbackError: Throwable) {
                writeDebugDownloadFailure("primary", error, "fallback", fallbackError)
                throw fallbackError
            }
        }
    }

    private fun writeDebugDownloadFailure(vararg failures: Any) {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        runCatching {
            File(app.filesDir, "last-subscription-download-error.txt").writeText(
                failures.joinToString("\n") { failure ->
                    when (failure) {
                        is Throwable -> Logs.sanitizeForLog(
                            "${failure.javaClass.name}: ${failure.message.orEmpty()}"
                        )
                        else -> Logs.sanitizeForLog(failure.toString())
                    }
                },
            )
        }
    }

    private fun downloadSubscription(
        request: Request,
        viaActiveProxy: Boolean,
        underlyingNetwork: android.net.Network? = null,
    ): DownloadedSubscription {
        // Active proxy mode intentionally lets the proxy resolve the provider host. The
        // physical-network fallback is the path that needs local DNS rebinding protection.
        if (underlyingNetwork != null) validateSubscriptionDestination(request.url, underlyingNetwork)
        val client = OkHttpClient.Builder()
            .callTimeout(SUBSCRIPTION_HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .apply {
                if (viaActiveProxy) useActiveVpnProxy()
                if (underlyingNetwork != null) useUnderlyingNetwork(underlyingNetwork)
            }
            // useUnderlyingNetwork installs its own resolver; override it only for direct
            // fallback so active proxy mode can resolve the provider through the proxy.
            .apply {
                if (underlyingNetwork != null) dns(publicSubscriptionDns(underlyingNetwork))
            }
            .build()
        var nextRequest = request
        repeat(MAX_SUBSCRIPTION_REDIRECTS + 1) { redirectAttempt ->
            val response = client.newCall(nextRequest).execute()
            if (response.isRedirect) {
                val location = response.header("Location")
                val redirectedUrl = location?.let { response.request.url.resolve(it) }
                response.close()
                if (redirectedUrl == null) {
                    throw SubscriptionUrlException("Subscription redirect has no valid location")
                }
                if (redirectAttempt >= MAX_SUBSCRIPTION_REDIRECTS) {
                    throw SubscriptionUrlException("Subscription redirect limit exceeded")
                }
                val safeUrl = validateSubscriptionUrl(redirectedUrl.toString())
                if (underlyingNetwork != null) {
                    validateSubscriptionDestination(safeUrl, underlyingNetwork)
                }
                nextRequest = nextRequest.newBuilder().url(safeUrl).build()
                return@repeat
            }
            return response.use {
                val contentType = it.header("Content-Type").orEmpty()
                val declaredLength = it.body?.contentLength() ?: -1L
                if (!it.isSuccessful) {
                    throw SubscriptionHttpException(it.code, contentType, declaredLength)
                }
                if (declaredLength > MAX_PROFILE_IMPORT_BYTES) {
                    throw SubscriptionResponseException(
                        "Subscription exceeds the maximum size",
                        contentType,
                        declaredLength,
                    )
                }
                val temporary = File.createTempFile("subscription-", ".tmp", app.cacheDir)
                try {
                    // The subscription URL is user-controlled. Stream into a bounded file before
                    // decoding so an absent/forged Content-Length cannot allocate an unbounded
                    // response body and OOM the updater process.
                    val body = it.body ?: throw SubscriptionResponseException(
                        "Subscription returned an empty response",
                        contentType,
                        0L,
                    )
                    var responseBytes = 0L
                    body.byteStream().use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                responseBytes = total
                                if (total > MAX_PROFILE_IMPORT_BYTES) {
                                    throw SubscriptionResponseException(
                                        "Subscription exceeds the maximum size",
                                        contentType,
                                        total,
                                    )
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (responseBytes == 0L) {
                        throw SubscriptionResponseException(
                            "Subscription returned an empty response",
                            contentType,
                            0L,
                        )
                    }
                    DownloadedSubscription(
                        content = temporary.inputStream().buffered().use {
                            it.readUtf8Limited(MAX_PROFILE_IMPORT_BYTES, "Subscription")
                        },
                        subscriptionUserInfo = it.header("Subscription-Userinfo").orEmpty(),
                        contentDisposition = it.header("content-disposition").orEmpty(),
                        contentType = contentType,
                        responseBytes = responseBytes,
                    )
                } finally {
                    temporary.delete()
                }
            }
        }
        throw SubscriptionUrlException("Subscription redirect limit exceeded")
    }

    private const val SUBSCRIPTION_HTTP_TIMEOUT_MS = 45_000L
}
