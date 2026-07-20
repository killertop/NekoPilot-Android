package io.nekohasekai.sagernet.bg

import android.os.Build
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols

internal object AutoSwitchPolicy {
    data class Candidate(val id: Long, val status: Int, val latencyMs: Int)
    data class BoundedCandidates(
        val ids: List<Long>,
        val exploredCount: Int,
        val explorationPoolSize: Int,
    )

    fun best(results: Map<Long, Int>): Long? = results
        .filterValues { it > 0 }
        .minWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
        ?.key

    fun liveCandidateIds(candidateIds: List<Long>, existingIds: Set<Long>): List<Long> =
        candidateIds.filter(existingIds::contains)

    fun boundedCandidateIds(
        candidates: List<Candidate>,
        selectedId: Long,
        explorationOffset: Int,
        limit: Int = 64,
        knownFastLimit: Int = 48,
    ): List<Long> = boundedCandidates(
        candidates,
        selectedId,
        explorationOffset,
        limit,
        knownFastLimit,
    ).ids

    fun boundedCandidates(
        candidates: List<Candidate>,
        selectedId: Long,
        explorationOffset: Int,
        limit: Int = 64,
        knownFastLimit: Int = 48,
    ): BoundedCandidates {
        if (candidates.size <= limit) return BoundedCandidates(
            ids = candidates.map(Candidate::id),
            exploredCount = 0,
            explorationPoolSize = 0,
        )
        val selected = candidates.firstOrNull { it.id == selectedId }
        val knownFast = candidates.asSequence()
            .filter { it.status == 1 && it.latencyMs > 0 && it.id != selectedId }
            .sortedWith(compareBy<Candidate>(Candidate::latencyMs).thenBy(Candidate::id))
            .take(knownFastLimit)
            .toList()
        val chosen = linkedSetOf<Long>()
        selected?.let { chosen += it.id }
        knownFast.forEach { chosen += it.id }
        val unexplored = candidates.asSequence()
            .filter { it.id !in chosen }
            .sortedBy(Candidate::id)
            .toList()
        val fixedCount = chosen.size
        if (unexplored.isNotEmpty()) {
            var index = Math.floorMod(explorationOffset, unexplored.size)
            while (chosen.size < limit && chosen.size < candidates.size) {
                chosen += unexplored[index].id
                index = (index + 1) % unexplored.size
            }
        }
        return BoundedCandidates(
            ids = chosen.toList(),
            exploredCount = chosen.size - fixedCount,
            explorationPoolSize = unexplored.size,
        )
    }

    fun nextExplorationOffset(current: Int, selection: BoundedCandidates): Int =
        if (selection.explorationPoolSize == 0) 0
        else Math.floorMod(
            current.toLong() + selection.exploredCount,
            selection.explorationPoolSize.toLong(),
        ).toInt()
}

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
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                withContext(Dispatchers.IO) {
                    DataStore.configurationStore.refreshBlocking()
                }
                if (!DataStore.autoSwitch) return@launch
                if (canTestNow()) testAndSwitch()
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
        val liveCandidates = SagerDatabase.proxyDao
            .getLatencyCandidates(ProxyEntity.TYPE_CONFIG)
        val boundedSelection = AutoSwitchPolicy.boundedCandidates(
            candidates = liveCandidates.map {
                AutoSwitchPolicy.Candidate(it.id, it.status, it.ping)
            },
            selectedId = DataStore.selectedProxy,
            explorationOffset = explorationOffset,
        )
        val boundedIds = boundedSelection.ids
        explorationOffset = AutoSwitchPolicy.nextExplorationOffset(
            explorationOffset,
            boundedSelection,
        )
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

        val bestId = AutoSwitchPolicy.best(results) ?: return
        if (bestId == DataStore.selectedProxy) return
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
        // A subscription can be refreshed while the test or idle wait is running.
        // Never select an outbound that has already disappeared from the database.
        val selected = SagerDatabase.proxyDao.getById(bestId) ?: return
        DataStore.selectedProxy = bestId
        DataStore.selectedGroup = selected.groupId
        withContext(Dispatchers.IO) {
            DataStore.configurationStore.flushBlocking()
        }
        if (!proxy.selectProfile(bestId)) {
            // An exploration winner may intentionally sit outside the 64 resident outbounds.
            // Persist it, then perform one controlled reload; the next bounded selector always
            // includes the selected profile.
            Logs.i("Auto switch reloading to activate explored profile $bestId")
            SelectedProfileReloadCoordinator.request(bestId, force = true)
            return
        }
        DataStore.currentProfile = bestId
        proxy.displayProfileName = ServiceNotification.genTitle(selected)
        onSelected(selected)
        Logs.i("Auto switch selected profile $bestId (${results[bestId]}ms)")
    }

    companion object {
        private const val INITIAL_DELAY_MS = 30_000L
        private const val CONNECTION_POLL_MS = 5_000L
        private const val CONNECTION_IDLE_WAIT_MS = 30_000L
        private const val TEST_TIMEOUT_MS = 3_000
        private const val TEST_INTERVAL_MS = 10 * 60_000L
    }
}
