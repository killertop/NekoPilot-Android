package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import android.content.Intent
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.SelectedProfileReloadCoordinator
import io.nekohasekai.sagernet.core.GoDataCore
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.displayNameForUi
import io.nekohasekai.sagernet.fmt.normalizeProfilesWithGo
import io.nekohasekai.sagernet.fmt.parseProfileDocumentWithGo
import io.nekohasekai.sagernet.fmt.parseSubscriptionDocumentWithGo
import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import androidx.core.net.toUri
import java.io.File

internal class SubscriptionIdentityIndex(
    existingBeansById: Map<Long, AbstractBean>,
    private val fingerprintOf: (AbstractBean) -> String = ::stableProviderFingerprint,
    private val identitiesEqual: (AbstractBean, AbstractBean) -> Boolean = { left, right -> left == right },
) {
    private data class IdentityClass(val key: String, val representative: AbstractBean)

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
        val identity = bean.providerIdentity()
        val fingerprint = fingerprintOf(identity)
        val classes = classesByFingerprint.getOrPut(fingerprint, ::arrayListOf)
        classes.firstOrNull { candidate ->
            identitiesEqual(candidate.representative, identity)
        }?.let { candidate -> return candidate.key }

        return "$fingerprint:${classes.size}".also { key ->
            classes += IdentityClass(key, identity)
        }
    }
}

private fun stableProviderFingerprint(identity: AbstractBean): String {
    return Libcore.providerIdentityFingerprint(
        identity.javaClass.name,
        KryoConverters.serialize(identity),
    )
}

private fun AbstractBean.providerIdentity(): AbstractBean = clone().apply {
    // A provider rename and device-local JSON overrides must not change server identity.
    name = ""
    customOutboundJson = ""
    customConfigJson = ""
}

internal fun preserveLocalOverridesAndDetectConfigChange(
    incoming: AbstractBean,
    existing: AbstractBean,
): Boolean {
    incoming.customOutboundJson = existing.customOutboundJson
    incoming.customConfigJson = existing.customConfigJson
    return existing != incoming
}

internal fun autoSwitchSelectorSetChanged(
    autoSwitch: Boolean,
    configUpdated: Boolean,
    added: Boolean,
    deleted: Boolean,
): Boolean = autoSwitch && (configUpdated || added || deleted)

internal fun preserveDeletionAfterPartialParse(
    hasNamedSkipped: Boolean,
    hasUnnamedSkipped: Boolean,
): Boolean = hasNamedSkipped || hasUnnamedSkipped

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

            val client = Libcore.newHttpClient().apply {
                setTimeout(SUBSCRIPTION_HTTP_TIMEOUT_MS)
                keepAlive()
                trySocks5(
                    DataStore.mixedPort,
                    DataStore.mixedProxyUsername,
                    DataStore.mixedProxyPassword
                )
            }
            try {
                val response = client.newRequest().apply {
                    setURL(subscription.link)
                    setUserAgent(subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)
                }.execute()
                val temporary = File.createTempFile("subscription-", ".tmp", app.cacheDir)
                try {
                    // The subscription URL is user-controlled. Stream into a bounded file before
                    // decoding so an absent/forged Content-Length cannot allocate an unbounded
                    // response body and OOM the updater process.
                    response.writeToProgressLimited(
                        temporary.canonicalPath,
                        MAX_PROFILE_IMPORT_BYTES.toLong(),
                        null,
                    )
                    val contentText = temporary.inputStream().buffered().use {
                        it.readUtf8Limited(MAX_PROFILE_IMPORT_BYTES, "Subscription")
                    }
                    val parsed = parseSubscriptionRaw(contentText)
                        ?: error(app.getString(R.string.no_proxies_found))
                    proxies = parsed.profiles
                    skippedProfileNames = parsed.skippedNames
                    hasUnnamedSkippedProfile = parsed.hasUnnamedSkipped
                } finally {
                    temporary.delete()
                }

                subscription.subscriptionUserinfo = SubscriptionMetadata.sanitizeUserInfo(
                    Util.getStringBox(response.getHeader("Subscription-Userinfo")),
                )

                // 修改默认名字
                val fallbackHost = subscription.link.toUri().host
                if (
                    proxyGroup.name?.startsWith("Subscription #") == true ||
                    (!fallbackHost.isNullOrBlank() && proxyGroup.name == fallbackHost)
                ) {
                    SubscriptionMetadata.displayName(
                        Util.getStringBox(response.getHeader("content-disposition")),
                    )?.let { remoteName -> proxyGroup.name = remoteName }
                }
            } finally {
                client.close()
            }
        }

        // Keep every server supplied by the subscription. De-duplication can silently
        // discard valid endpoints that share an address or protocol shape.
        val normalized = normalizeProfilesWithGo(proxies, false)
        proxies = normalized.profiles

        require(proxies.size <= GoDataCore.MAX_SUBSCRIPTION_PROFILES) {
            app.getString(
                R.string.subscription_too_many_nodes,
                GoDataCore.MAX_SUBSCRIPTION_PROFILES,
            )
        }
        val existingCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id)
        require(existingCount <= GoDataCore.MAX_SUBSCRIPTION_PROFILES) {
            app.getString(
                R.string.subscription_too_many_nodes,
                GoDataCore.MAX_SUBSCRIPTION_PROFILES,
            )
        }

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)
        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        // Public preferences are Room-backed but each process keeps its own cache. Periodic
        // updates run in :bg, so refresh before deciding which selected/active node is affected.
        DataStore.configurationStore.refreshBlocking()
        val selectedBeforeId = DataStore.selectedProxy
        val activeBeforeId = DataStore.currentProfile
        val duplicate = emptyList<String>()
        val partialParse = skippedProfileNames.isNotEmpty() || hasUnnamedSkippedProfile

        Logs.d("New profiles: ${proxies.size}")

        // Go owns deterministic identity/name matching and the resulting diff plan. Kotlin
        // keeps exact protocol-bean equality because it is part of the persisted ABI.
        val existingById = exists.associateBy(ProxyEntity::id)
        val existingBeansById = exists.associate { entity -> entity.id to entity.requireBean() }
        val identityIndex = SubscriptionIdentityIndex(existingBeansById)
        val updatePlan = GoDataCore.planSubscriptionUpdate(
            incoming = proxies.map { bean ->
                val name = bean.displayNameForUi()
                // AbstractBean equality intentionally ignores display names. The registry also
                // excludes local JSON overrides and collapses identical fingerprints into one
                // verified identity class, avoiding quadratic duplicate-node comparisons.
                GoDataCore.SubscriptionIncoming(name, identityIndex.identityForIncoming(bean))
            },
            existing = exists.map { entity ->
                GoDataCore.SubscriptionExisting(
                    id = entity.id,
                    name = entity.displayName(),
                    userOrder = entity.userOrder,
                    identity = identityIndex.identityForExisting(entity.id),
                )
            },
        )
        require(updatePlan.actions.size == proxies.size) { "Go subscription plan is incomplete" }

        val toUpdate = ArrayList<ProxyEntity>()
        val toAdd = ArrayList<ProxyEntity>()
        val configUpdatedIds = hashSetOf<Long>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        var nextPartialOrder = (exists.maxOfOrNull(ProxyEntity::userOrder) ?: 0L) + 1L
        // The Go plan owns the target order for every incoming profile.
        var changed = 0
        for (action in updatePlan.actions) {
            require(action.incomingIndex in proxies.indices) { "Go subscription plan has an invalid profile" }
            val bean = proxies[action.incomingIndex]
            val name = bean.displayNameForUi()
            val entity = action.existingId?.let(existingById::get)
            if (entity != null) {
                val existsBean = existingBeansById.getValue(entity.id)
                val oldName = entity.displayName()
                // 更新订阅，保留自定义覆写设置
                val configChanged = preserveLocalOverridesAndDetectConfigChange(bean, existsBean)
                when (action.action) {
                    GoDataCore.SubscriptionActionKind.UPDATE -> {
                        changed++
                        entity.putBean(bean)
                        if (!partialParse) entity.userOrder = action.userOrder
                        if (configChanged) {
                            // Endpoint/auth changes invalidate the old latency and availability;
                            // a display-name-only update keeps both the measurement and connection.
                            entity.status = 0
                            entity.ping = 0
                            entity.error = null
                            configUpdatedIds += entity.id
                        }
                        toUpdate.add(entity)
                        updated[oldName] = name
                    }

                    GoDataCore.SubscriptionActionKind.REORDER -> {
                        if (!partialParse) {
                            toUpdate.add(entity)
                            entity.userOrder = action.userOrder
                        }

                    }

                    GoDataCore.SubscriptionActionKind.UNCHANGED -> require(
                        partialParse || entity.userOrder == action.userOrder
                    ) {
                        "Go subscription plan marked a changed profile as unchanged"
                    }

                    GoDataCore.SubscriptionActionKind.ADD -> error(
                        "Go subscription plan mismatched an added profile"
                    )
                }
            } else {
                require(action.action == GoDataCore.SubscriptionActionKind.ADD) {
                    "Go subscription plan refers to an unknown profile"
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
                    ?: error("Go subscription plan deletes an unknown profile")
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
        val deleted = toDelete.map { it.displayName() }
        changed += toDelete.size

        if (preservedFromPartialParse.isNotEmpty()) {
            Logs.w("Preserved ${preservedFromPartialParse.size} profiles after a partial subscription parse")
        }

        SagerDatabase.proxyDao.applySubscriptionChanges(toAdd, toUpdate, toDelete)
        Logs.d("Added profiles: ${toAdd.size}")
        Logs.d("Updated profiles: ${toUpdate.size}")
        Logs.d("Deleted profiles: ${toDelete.size}")

        val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

        val expectedExistCount = proxies.size + preservedFromPartialParse.size
        if (existCount != expectedExistCount) {
            Logs.e("Exist profiles: $existCount, expected profiles: $expectedExistCount")
        }

        // Only fill an empty or stale selection. Updating a different subscription must
        // never silently switch the user's chosen node.
        val selectedProfile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
        val selectionRecovered = if (
            GoDataCore.requiresSubscriptionSelectionFallback(selectedProfile != null)
        ) {
            SagerDatabase.proxyDao.getAll().minWithOrNull(
                compareBy<ProxyEntity> {
                    if (it.status == 1 && it.ping > 0) 0 else 1
                }.thenBy {
                    if (it.status == 1 && it.ping > 0) it.ping else Int.MAX_VALUE
                }.thenBy(ProxyEntity::id)
            )?.let {
                DataStore.selectedProxy = it.id
                DataStore.selectedGroup = it.groupId
            } ?: run {
                DataStore.selectedProxy = 0L
                DataStore.selectedGroup = 0L
            }
            true
        } else false
        if (selectionRecovered) DataStore.configurationStore.flushBlocking()

        val selectedAfterId = DataStore.selectedProxy
        val activeWasUpdated = activeBeforeId != 0L && activeBeforeId in configUpdatedIds
        val activeWasDeleted = toDelete.any { it.id == activeBeforeId && it.id != 0L }
        val selectorSetChanged = autoSwitchSelectorSetChanged(
            autoSwitch = DataStore.autoSwitch,
            configUpdated = configUpdatedIds.isNotEmpty(),
            added = toAdd.isNotEmpty(),
            deleted = toDelete.isNotEmpty(),
        )
        if (DataStore.serviceState.started && (
            activeWasUpdated || activeWasDeleted ||
            selectedAfterId != activeBeforeId || selectorSetChanged
        )) {
            if (selectedAfterId > 0L) {
                SelectedProfileReloadCoordinator.request(
                    selectedAfterId,
                    force = (activeWasUpdated && selectedAfterId == activeBeforeId) ||
                        selectorSetChanged,
                )
            } else {
                SagerNet.stopService()
            }
        }

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
        val profiles = parseProfileDocumentWithGo(text).takeIf { it.isNotEmpty() } ?: return null
        if (fileName.isNotBlank() && profiles.size == 1 && profiles[0].name.isBlank()) {
            profiles[0].name = fileName.substringBeforeLast('.')
        }
        return profiles
    }

    private fun parseSubscriptionRaw(text: String) =
        parseSubscriptionDocumentWithGo(text).takeIf { it.profiles.isNotEmpty() }

    private const val SUBSCRIPTION_HTTP_TIMEOUT_MS = 45_000L
}
