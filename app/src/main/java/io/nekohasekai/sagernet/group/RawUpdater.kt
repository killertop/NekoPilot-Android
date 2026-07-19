package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.core.RustDataCore
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.normalizeProfilesWithGo
import io.nekohasekai.sagernet.fmt.parseProfileDocumentWithGo
import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import androidx.core.net.toUri

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
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())?.use {
                it.readUtf8Limited(MAX_PROFILE_IMPORT_BYTES, "Subscription")
            }

            proxies = contentText?.let { parseRaw(contentText) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {

            val client = Libcore.newHttpClient().apply {
                setTimeout(SUBSCRIPTION_HTTP_TIMEOUT_MS)
                keepAlive()
                trySocks5(
                    DataStore.mixedPort,
                    DataStore.mixedProxyUsername,
                    DataStore.mixedProxyPassword
                )
                tryH3Direct()
                when (DataStore.appTLSVersion) {
                    "1.3" -> restrictedTLS()
                }
            }
            try {
                val response = client.newRequest().apply {
                    if (DataStore.allowInsecureOnRequest) {
                        allowInsecure()
                    }
                    setURL(subscription.link)
                    setUserAgent(subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)
                }.execute()
                proxies = parseRaw(Util.getStringBox(response.contentString))
                    ?: error(app.getString(R.string.no_proxies_found))

                subscription.subscriptionUserinfo =
                    Util.getStringBox(response.getHeader("Subscription-Userinfo"))

                // 修改默认名字
                if (proxyGroup.name?.startsWith("Subscription #") == true) {
                    var remoteName = Util.getStringBox(response.getHeader("content-disposition"))
                    if (remoteName.isNotBlank()) {
                        remoteName = Util.decodeFilename(remoteName)
                        if (remoteName.isNotBlank()) {
                            proxyGroup.name = remoteName
                        }
                    }
                }
            } finally {
                client.close()
            }
        }

        // Keep every server supplied by the subscription. De-duplication can silently
        // discard valid endpoints that share an address or protocol shape.
        val normalized = normalizeProfilesWithGo(proxies, false)
        proxies = normalized.profiles

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = emptyList<String>()

        Logs.d("New profiles: ${proxies.size}")

        // Rust owns deterministic duplicate-name matching and the resulting diff plan.
        // Kotlin keeps exact protocol-bean equality because it is part of the persisted ABI.
        val existingByName = exists.groupBy(ProxyEntity::displayName)
        val existingById = exists.associateBy(ProxyEntity::id)
        val updatePlan = RustDataCore.planSubscriptionUpdate(
            incoming = proxies.map { bean ->
                val name = bean.displayName()
                val equalExistingIds = existingByName[name].orEmpty().mapNotNull { entity ->
                    val existingBean = entity.requireBean()
                    val candidate = bean.clone().apply {
                        customOutboundJson = existingBean.customOutboundJson
                        customConfigJson = existingBean.customConfigJson
                    }
                    entity.id.takeIf { existingBean == candidate }
                }
                RustDataCore.SubscriptionIncoming(name, equalExistingIds)
            },
            existing = exists.map { entity ->
                RustDataCore.SubscriptionExisting(
                    id = entity.id,
                    name = entity.displayName(),
                    userOrder = entity.userOrder,
                )
            },
        )
        require(updatePlan.actions.size == proxies.size) { "Rust subscription plan is incomplete" }

        val toUpdate = ArrayList<ProxyEntity>()
        val toAdd = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        // The Rust plan owns the target order for every incoming profile.
        var changed = 0
        for (action in updatePlan.actions) {
            require(action.incomingIndex in proxies.indices) { "Rust subscription plan has an invalid profile" }
            val bean = proxies[action.incomingIndex]
            val name = bean.displayName()
            val entity = action.existingId?.let(existingById::get)
            if (entity != null) {
                val existsBean = entity.requireBean()
                // 更新订阅，保留自定义覆写设置
                bean.customOutboundJson = existsBean.customOutboundJson
                bean.customConfigJson = existsBean.customConfigJson
                when (action.action) {
                    RustDataCore.SubscriptionActionKind.UPDATE -> {
                        changed++
                        entity.putBean(bean)
                        entity.userOrder = action.userOrder
                        toUpdate.add(entity)
                        updated[entity.displayName()] = name

                    }

                    RustDataCore.SubscriptionActionKind.REORDER -> {
                        toUpdate.add(entity)
                        entity.userOrder = action.userOrder

                    }

                    RustDataCore.SubscriptionActionKind.UNCHANGED -> require(entity.userOrder == action.userOrder) {
                        "Rust subscription plan marked a changed profile as unchanged"
                    }

                    RustDataCore.SubscriptionActionKind.ADD -> error(
                        "Rust subscription plan mismatched an added profile"
                    )
                }
            } else {
                require(action.action == RustDataCore.SubscriptionActionKind.ADD) {
                    "Rust subscription plan refers to an unknown profile"
                }
                changed++
                toAdd.add(
                    ProxyEntity(
                        groupId = proxyGroup.id, userOrder = action.userOrder
                    ).apply {
                        putBean(bean)
                    })
                added.add(name)
            }
        }

        val toDelete = ArrayList<ProxyEntity>().apply {
            updatePlan.deletionIds.forEach { profileId ->
                add(existingById[profileId] ?: error("Rust subscription plan deletes an unknown profile"))
            }
        }
        val deleted = toDelete.map { it.displayName() }
        changed += toDelete.size

        SagerDatabase.proxyDao.applySubscriptionChanges(toAdd, toUpdate, toDelete)
        Logs.d("Added profiles: ${toAdd.size}")
        Logs.d("Updated profiles: ${toUpdate.size}")
        Logs.d("Deleted profiles: ${toDelete.size}")

        val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

        if (existCount != proxies.size) {
            Logs.e("Exist profiles: $existCount, new profiles: ${proxies.size}")
        }

        // Only fill an empty or stale selection. Updating a different subscription must
        // never silently switch the user's chosen node.
        val selectedProfile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
        if (RustDataCore.requiresSubscriptionSelectionFallback(selectedProfile != null)) {
            SagerDatabase.proxyDao.getByGroup(proxyGroup.id).firstOrNull()?.let {
                DataStore.selectedProxy = it.id
            }
        }

        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        SagerDatabase.groupDao.updateGroup(proxyGroup)
        finishUpdate(proxyGroup)

        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, deleted, duplicate, byUser
        )
    }


    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? {
        require(text.length <= MAX_PROFILE_IMPORT_BYTES) { "Profile input is too large" }
        val profiles = parseProfileDocumentWithGo(text).takeIf { it.isNotEmpty() } ?: return null
        if (fileName.isNotBlank() && profiles.size == 1 && profiles[0].name.isBlank()) {
            profiles[0].name = fileName.substringBeforeLast('.')
        }
        return profiles
    }

    private const val SUBSCRIPTION_HTTP_TIMEOUT_MS = 45_000L
}
