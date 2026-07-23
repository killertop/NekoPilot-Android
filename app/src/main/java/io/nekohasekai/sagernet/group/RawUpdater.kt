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
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

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

        val link = subscription.link
        var proxies: List<AbstractBean>
        var skippedProfileNames = emptySet<String>()
        var hasUnnamedSkippedProfile = false
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())?.use {
                it.readUtf8Limited(MAX_PROFILE_IMPORT_BYTES, "Subscription")
            }

            val parsed = contentText?.let(::parseSubscriptionRaw)
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
            proxies = parsed.profiles
            skippedProfileNames = parsed.skippedNames
            hasUnnamedSkippedProfile = parsed.hasUnnamedSkipped
        } else {

            val request = Request.Builder()
                .url(subscription.link)
                .header("User-Agent", subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)
                .build()
            val downloaded = downloadSubscriptionWithFallback(request)
            val parsed = parseSubscriptionRaw(downloaded.content)
                ?: error(app.getString(R.string.no_proxies_found))
            proxies = parsed.profiles
            skippedProfileNames = parsed.skippedNames
            hasUnnamedSkippedProfile = parsed.hasUnnamedSkipped

            subscription.subscriptionUserinfo = SubscriptionMetadata.sanitizeUserInfo(
                downloaded.subscriptionUserInfo,
            )

            // 修改默认名字
            val fallbackHost = subscription.link.toUri().host
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
                            entity.status = 0
                            entity.ping = 0
                            entity.error = null
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

        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        SagerDatabase.groupDao.updateGroup(proxyGroup)

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
                        is Throwable -> "${failure.javaClass.name}: ${failure.message.orEmpty()}"
                        else -> failure.toString()
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
        val client = OkHttpClient.Builder()
            .callTimeout(SUBSCRIPTION_HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .apply {
                if (viaActiveProxy) useActiveVpnProxy()
                if (underlyingNetwork != null) useUnderlyingNetwork(underlyingNetwork)
            }
            .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Subscription returned HTTP ${response.code}" }
            require(response.body?.contentLength()?.let { it <= MAX_PROFILE_IMPORT_BYTES } != false) {
                "Subscription exceeds the maximum size"
            }
            val temporary = File.createTempFile("subscription-", ".tmp", app.cacheDir)
            try {
                // The subscription URL is user-controlled. Stream into a bounded file before
                // decoding so an absent/forged Content-Length cannot allocate an unbounded
                // response body and OOM the updater process.
                response.body?.byteStream()?.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_PROFILE_IMPORT_BYTES) {
                                "Subscription exceeds the maximum size"
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: error("Subscription returned an empty response")
                DownloadedSubscription(
                    content = temporary.inputStream().buffered().use {
                        it.readUtf8Limited(MAX_PROFILE_IMPORT_BYTES, "Subscription")
                    },
                    subscriptionUserInfo = response.header("Subscription-Userinfo").orEmpty(),
                    contentDisposition = response.header("content-disposition").orEmpty(),
                )
            } finally {
                temporary.delete()
            }
        }
    }

    private const val SUBSCRIPTION_HTTP_TIMEOUT_MS = 45_000L
}
