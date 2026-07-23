package io.nekohasekai.sagernet.database

import android.content.Intent
import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.GroupManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.io.IOException
import java.sql.SQLException
import kotlinx.coroutines.CancellationException

internal suspend fun <T> dispatchListenerSnapshot(
    listeners: List<T>,
    category: String,
    logger: (String) -> Unit = { Logs.w(it) },
    notify: suspend T.() -> Unit,
) {
    listeners.forEach { listener ->
        try {
            notify(listener)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A listener runs after the database mutation has committed. Do not expose its
            // message or stack trace (a listener may include server data), and never let a stale
            // screen turn a successful database operation into a reported failure.
            try {
                logger("$category listener failed (${error.javaClass.simpleName})")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Logging is best effort and must not change the completed operation's result.
            }
        }
    }
}

object ProfileManager {

    private const val RULE_DEFAULTS_VERSION = 1

    /**
     * Returns the persisted selection or repairs an empty/stale selection from Room.
     *
     * Subscription import and the VPN service can run in different processes. Each process has
     * its own preference cache, so the database is the source of truth at the connection boundary.
     */
    suspend fun ensureValidSelection(preferredGroupId: Long? = null): ProxyEntity? =
        run {
            repeat(4) {
                val observed = DataStore.readProxySelection()
                SagerDatabase.proxyDao.getById(observed.profileId)?.let { return it }

                val preferred = preferredGroupId?.takeIf { it > 0L }
                    ?: observed.groupId.takeIf { it > 0L }
                    ?: -1L
                val fallback = SagerDatabase.proxyDao.getNodeList().connectionFallback(
                    preferredGroupId = preferred,
                    groupId = ProxyEntity.NodeListItem::groupId,
                )
                val replacement = ProxySelection(
                    profileId = fallback?.id ?: 0L,
                    groupId = fallback?.groupId ?: 0L,
                )
                if (DataStore.compareAndSetProxySelection(observed, replacement)) {
                    if (fallback == null) return null
                    SagerDatabase.proxyDao.getById(fallback.id)?.let { return it }
                }
                // Another process changed the selection after our snapshot. Re-read it and
                // return that valid choice instead of publishing this stale fallback.
            }
            DataStore.readProxySelection().profileId
                .takeIf { it > 0L }
                ?.let(SagerDatabase.proxyDao::getById)
        }

    private suspend fun selectInitialProfileIfMissing(profile: ProxyEntity): Boolean {
        repeat(4) {
            val observed = DataStore.readProxySelection()
            if (SagerDatabase.proxyDao.getById(observed.profileId) != null) return false
            if (DataStore.compareAndSetProxySelection(
                    observed,
                    ProxySelection(profile.id, profile.groupId),
                )
            ) return true
        }
        return false
    }

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(profile: ProxyEntity)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        dispatchListenerSnapshot(snapshot, "Profile", notify = what)
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val snapshot = synchronized(ruleListeners) { ruleListeners.toList() }
        dispatchListenerSnapshot(snapshot, "Rule", notify = what)
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        // A freshly imported node must be immediately connectable.  Preserve an existing valid
        // choice, but recover an empty/stale selection at the centralized creation boundary so
        // clipboard, deep-link, QR, and editor imports all behave identically.
        selectInitialProfileIfMissing(profile)
        iterator { onAdd(profile) }
        return profile
    }

    suspend fun createProfiles(groupId: Long, beans: List<AbstractBean>): List<ProxyEntity> {
        if (beans.isEmpty()) return emptyList()
        val profiles = beans.map { bean ->
            // Keep every bulk-import path as safe as the single-node path. Some parsers already
            // initialize their beans, but callers should not have to know that implementation
            // detail before persisting a connectable profile.
            bean.applyDefaultValues()
            ProxyEntity(groupId = groupId).apply { putBean(bean) }
        }
        val ids = SagerDatabase.proxyDao.addProxyBatch(groupId, profiles)
        check(ids.size == profiles.size) { "Profile batch insert is incomplete" }
        profiles.forEachIndexed { index, profile -> profile.id = ids[index] }

        selectInitialProfileIfMissing(profiles.first())
        // One group event replaces thousands of per-row callbacks and gives Home a single,
        // consistent snapshot after the transaction commits.
        GroupManager.postReload(groupId)
        return profiles
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        val previous = SagerDatabase.proxyDao.getById(profile.id)
        if (
            previous != null &&
            (previous.type != profile.type || previous.requireBean() != profile.requireBean())
        ) {
            profile.status = 0
            profile.ping = 0
            profile.error = null
            profile.downloadMbps = null
        }
        SagerDatabase.proxyDao.updateProxy(profile)
        iterator { onUpdated(profile) }
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        if (profiles.isEmpty()) return
        SagerDatabase.proxyDao.updateProxy(profiles)
        iterator {
            for (profile in profiles) onUpdated(profile)
        }
    }

    /**
     * Saves only volatile test metadata, and only while the tested server definition still
     * matches the database. This prevents a late speed-test result from resurrecting old node
     * settings after an edit or subscription refresh.
     */
    suspend fun updateTestResults(
        results: Collection<ProxyEntity>,
        notifyListeners: Boolean = true,
    ): List<ProxyEntity> {
        if (results.isEmpty()) return emptyList()
        val uniqueResults = results.distinctBy(ProxyEntity::id)
        val persistedIds = SagerDatabase.proxyDao.updateTestResultsIfUnchanged(uniqueResults)
        if (persistedIds.isEmpty()) return emptyList()
        // The speed-test screen already applied each result as it arrived. Its final save uses
        // notifyListeners=false, so materializing every full proxy row again only repeats bean
        // deserialization and allocations without any consumer for the returned entities.
        if (!notifyListeners) return emptyList()
        val resultById = uniqueResults.associateBy(ProxyEntity::id)
        val persisted = SagerDatabase.proxyDao
            .getEntities(persistedIds)
            .onEach { current ->
                current.downloadMbps = resultById[current.id]?.downloadMbps
            }
        if (notifyListeners) {
            iterator {
                for (profile in persisted) onUpdated(profile)
            }
            if (app.process.endsWith(":bg")) {
                app.sendBroadcast(
                    Intent(Action.PROFILES_CHANGED).setPackage(app.packageName),
                    "${app.packageName}.permission.SERVICE_CONTROL",
                )
            }
        }
        return persisted
    }

    suspend fun deleteProfilesSilently(
        profiles: List<ProxyEntity>,
        connectionStarted: Boolean,
    ): SelectionRepairAction {
        if (profiles.isEmpty()) return SelectionRepairAction.None
        SagerDatabase.proxyDao.deleteProxy(profiles)
        return reselectAfterRemoval(
            profiles.mapTo(hashSetOf(), ProxyEntity::id),
            connectionStarted = connectionStarted,
        )
    }

    suspend fun deleteProfile(
        groupId: Long,
        profileId: Long,
        connectionStarted: Boolean,
    ): SelectionRepairAction {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) {
            return SelectionRepairAction.None
        }
        val action = reselectAfterRemoval(
            setOf(profileId),
            connectionStarted = connectionStarted,
        )
        iterator { onRemoved(groupId, profileId) }
        if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
        return action
    }

    suspend fun deleteProfiles(
        profiles: List<ProxyEntity>,
        connectionStarted: Boolean,
    ): SelectionRepairAction {
        if (profiles.isEmpty()) return SelectionRepairAction.None
        SagerDatabase.proxyDao.deleteProxy(profiles)
        val action = reselectAfterRemoval(
            profiles.mapTo(hashSetOf(), ProxyEntity::id),
            connectionStarted = connectionStarted,
        )
        iterator {
            for (profile in profiles) onRemoved(profile.groupId, profile.id)
        }
        for (groupId in profiles.mapTo(linkedSetOf(), ProxyEntity::groupId)) {
            if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) GroupManager.rearrange(groupId)
        }
        return action
    }

    /**
     * Keeps Home immediately connectable after deleting the selected node/source. The VPN is
     * stopped, but the best remaining measured node becomes selected without auto-connecting.
     */
    internal suspend fun reselectAfterRemoval(
        removedProfileIds: Set<Long> = emptySet(),
        removedGroupIds: Set<Long> = emptySet(),
        connectionStarted: Boolean,
    ): SelectionRepairAction {
        val observedSelection = DataStore.readProxySelection()
        val selected = SagerDatabase.proxyDao.getById(observedSelection.profileId)
        val active = SagerDatabase.proxyDao.getById(DataStore.currentProfile)
        val selectionRemoved = selected == null ||
            selected.id in removedProfileIds || selected.groupId in removedGroupIds
        val activeRemoved = DataStore.currentProfile > 0L && (
            active == null || active.id in removedProfileIds || active.groupId in removedGroupIds
        )
        if (!selectionRemoved && !activeRemoved) return SelectionRepairAction.None

        if (selectionRemoved) {
            val fallback = SagerDatabase.proxyDao.getNodeList().asSequence()
                .filter { it.id !in removedProfileIds && it.groupId !in removedGroupIds }
                .firstOrNull()
            DataStore.compareAndSetProxySelection(
                observedSelection,
                ProxySelection(fallback?.id ?: 0L, fallback?.groupId ?: 0L),
            )
        }
        val latestProfileId = if (connectionStarted && activeRemoved && !selectionRemoved) {
            DataStore.readProxySelection().profileId
        } else 0L
        return selectionRepairAction(
            connectionStarted = connectionStarted,
            activeRemoved = activeRemoved,
            selectionRemoved = selectionRemoved,
            selectedProfileId = latestProfileId,
        )
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            SagerDatabase.proxyDao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            SagerDatabase.proxyDao.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long) {
        postUpdate(getProfile(profileId) ?: return)
    }

    suspend fun postUpdate(profile: ProxyEntity) {
        iterator { onUpdated(profile) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
        rule.id = SagerDatabase.rulesDao.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        SagerDatabase.rulesDao.updateRule(rule)
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        val rule = SagerDatabase.rulesDao.getById(ruleId) ?: return
        if (rule.isDefaultChinaDirectRule()) return
        if (SagerDatabase.rulesDao.deleteById(ruleId) == 0) return
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        val removable = rules.filterNot(RuleEntity::isDefaultChinaDirectRule)
        if (removable.isEmpty()) return
        SagerDatabase.rulesDao.deleteRules(removable)
        ruleIterator {
            removable.forEach {
                onRemoved(it.id)
            }
        }
    }

    fun getRules(): List<RuleEntity> {
        var rules = SagerDatabase.rulesDao.ensureDefaultChinaRules(
            app.getString(R.string.route_china_domain),
            app.getString(R.string.route_china_ip),
        )
        // The version gate enables defaults once on upgrade while preserving a later explicit
        // user choice to turn either direct rule off.
        if (DataStore.ruleDefaultsVersion < RULE_DEFAULTS_VERSION) {
            val rulesToEnable = rules.filter { !it.enabled && it.isDefaultChinaDirectRule() }
            if (rulesToEnable.isNotEmpty()) {
                rulesToEnable.forEach { it.enabled = true }
                SagerDatabase.rulesDao.updateRules(rulesToEnable)
                rules = SagerDatabase.rulesDao.allRules()
            }
            DataStore.ruleDefaultsVersion = RULE_DEFAULTS_VERSION
        }
        return rules
    }

}
