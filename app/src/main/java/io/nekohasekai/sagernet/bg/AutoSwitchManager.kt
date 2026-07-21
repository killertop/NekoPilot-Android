package io.nekohasekai.sagernet.bg

import android.os.Build
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.core.GoDataCore
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols

/**
 * Tests the runtime selector's complete profile set with one disposable core.
 * The selected outbound is changed only after the main core has no live TCP or
 * UDP connections, so a long-lived stream or call is never reset automatically.
 */
class AutoSwitchManager(
    private val scope: CoroutineScope,
    private val proxy: ProxyInstance,
    private val onSelected: suspend (ProxyEntity) -> Unit = {},
) {
    private var job: Job? = null
    private var explorationOffset = 0

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                try {
                    withContext(Dispatchers.IO) {
                        DataStore.configurationStore.refreshBlocking()
                    }
                    if (!DataStore.autoSwitch) return@launch
                    if (canTestNow()) testAndSwitch()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // A malformed persisted row or Go bridge error must not permanently kill the
                    // periodic manager. The next interval gets a clean database snapshot.
                    Logs.w("Automatic node selection failed; it will retry later", error)
                }
                delay(TEST_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun canTestNow(): Boolean {
        val power = SagerNet.power
        val idle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && power.isDeviceIdleMode
        return power.isInteractive && !idle
    }

    private suspend fun testAndSwitch() {
        // Read only id/status/latency for the full airport. Decoding every protocol bean and
        // keeping thousands of native outbounds resident makes connection startup scale with the
        // subscription size. The test core and main selector both stay bounded to 64 profiles.
        val selectedProfileId = DataStore.selectedProxy
        val liveCandidates = SagerDatabase.proxyDao
            .getLatencyCandidates(
                ProxyEntity.TYPE_CONFIG,
                selectedProfileId,
                GoDataCore.MAX_AUTO_SWITCH_CANDIDATES,
            )
        val boundedSelection = GoDataCore.planAutoSwitchCandidates(
            candidates = liveCandidates.map {
                GoDataCore.AutoSwitchCandidate(it.id, it.status, it.ping)
            },
            selectedId = selectedProfileId,
            explorationOffset = explorationOffset,
        )
        val boundedIds = boundedSelection.ids
        explorationOffset = boundedSelection.nextExplorationOffset
        val profilesById = SagerDatabase.proxyDao.getEntities(boundedIds)
            .associateBy(ProxyEntity::id)
        val testCandidates = boundedIds.mapNotNull(profilesById::get)
        if (testCandidates.size < 2) return

        val results = linkedMapOf<Long, Int>()
        val changed = linkedMapOf<Long, ProxyEntity>()
        val runner = TestInstance(
            profile = testCandidates.first(),
            link = CONNECTION_TEST_URL,
            timeout = TEST_TIMEOUT_MS,
            testProfiles = testCandidates,
            downloadEnabled = false,
        )
        try {
            runner.runBatch(
                testCandidates,
                onResult = { profile, result ->
                    results[profile.id] = result.latencyMs
                    profile.status = 1
                    profile.ping = result.latencyMs
                    profile.error = null
                    changed[profile.id] = profile
                },
                onError = { profile, error ->
                    profile.status = 3
                    profile.ping = 0
                    profile.error = Protocols.genFriendlyMsg(error.message.orEmpty())
                    changed[profile.id] = profile
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            return
        } finally {
            if (changed.isNotEmpty()) ProfileManager.updateTestResults(changed.values)
        }

        val bestId = GoDataCore.selectBestLatency(results) ?: return
        if (bestId == DataStore.selectedProxy && bestId == DataStore.currentProfile) return
        Logs.i("Auto switch waiting for active connections before selecting profile $bestId")
        var waitedMs = 0L
        while (
            scope.isActive && proxy.box.activeConnections() > 0L &&
            waitedMs < CONNECTION_IDLE_WAIT_MS
        ) {
            delay(CONNECTION_POLL_MS)
            waitedMs += CONNECTION_POLL_MS
        }
        if (!scope.isActive) return
        if (proxy.box.activeConnections() > 0L) {
            Logs.i("Auto switch skipped: active connections did not become idle")
            return
        }
        // Testing and the idle wait can take tens of seconds. Respect a user selection made in
        // that window instead of allowing a stale automatic result to overwrite it.
        withContext(Dispatchers.IO) {
            DataStore.configurationStore.refreshBlocking()
        }
        if (!DataStore.autoSwitch || DataStore.selectedProxy != selectedProfileId) {
            Logs.i("Auto switch skipped because the selected profile changed")
            return
        }
        // A subscription can be refreshed while the test or idle wait is running.
        // Never select an outbound that has already disappeared from the database.
        val selected = SagerDatabase.proxyDao.getById(bestId) ?: return
        val previousSelectedId = DataStore.selectedProxy
        val previousGroupId = DataStore.selectedGroup
        val selectedInPlace = proxy.selectProfile(bestId)
        try {
            persistSelection(bestId, selected.groupId)
            if (!selectedInPlace) {
                // An exploration winner may intentionally sit outside the 64 resident outbounds.
                // Persist it, then perform one controlled reload; a failed reload is retried
                // because currentProfile remains different from selectedProxy.
                Logs.i("Auto switch reloading to activate explored profile $bestId")
                SelectedProfileReloadCoordinator.request(bestId, force = true)
                return
            }
        } catch (error: Exception) {
            if (selectedInPlace && previousSelectedId > 0L) {
                try {
                    check(proxy.selectProfile(previousSelectedId)) {
                        "Previous profile is no longer resident"
                    }
                } catch (rollbackError: Exception) {
                    Logs.w("Failed to restore the previous runtime profile", rollbackError)
                }
            }
            restorePersistedSelection(previousSelectedId, previousGroupId)
            throw error
        }
        DataStore.currentProfile = bestId
        proxy.displayProfileName = ServiceNotification.genTitle(selected)
        onSelected(selected)
        Logs.i("Auto switch selected profile $bestId (${results[bestId]}ms)")
    }

    private suspend fun persistSelection(profileId: Long, groupId: Long) {
        DataStore.selectedProxy = profileId
        DataStore.selectedGroup = groupId
        withContext(Dispatchers.IO) {
            DataStore.configurationStore.flushBlocking()
        }
    }

    private suspend fun restorePersistedSelection(profileId: Long, groupId: Long) {
        DataStore.selectedProxy = profileId
        DataStore.selectedGroup = groupId
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                DataStore.configurationStore.flushBlocking()
            }
        } catch (rollbackError: Exception) {
            Logs.w("Failed to restore the previous persisted profile", rollbackError)
        }
    }

    companion object {
        private const val INITIAL_DELAY_MS = 30_000L
        private const val CONNECTION_POLL_MS = 5_000L
        private const val CONNECTION_IDLE_WAIT_MS = 30_000L
        private const val TEST_TIMEOUT_MS = 3_000
        private const val TEST_INTERVAL_MS = 10 * 60_000L
    }
}
