package io.nekohasekai.sagernet.bg

import android.os.Build
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.Protocols

internal object AutoSwitchPolicy {
    fun best(results: Map<Long, Int>): Long? = results
        .filterValues { it > 0 }
        .minWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
        ?.key

    fun liveCandidateIds(candidateIds: List<Long>, existingIds: Set<Long>): List<Long> =
        candidateIds.filter(existingIds::contains)
}

/**
 * Tests the runtime selector's complete profile set with one disposable core.
 * The selected outbound is changed only after the main core has no live TCP or
 * UDP connections, so a long-lived stream or call is never reset automatically.
 */
class AutoSwitchManager(
    private val scope: CoroutineScope,
    private val proxy: ProxyInstance,
    private val candidates: List<ProxyEntity>,
    private val onSelected: suspend (ProxyEntity) -> Unit = {},
) {
    private var job: Job? = null

    fun start() {
        if (job != null || candidates.size < 2) return
        job = scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                DataStore.configurationStore.refreshBlocking()
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
        val candidateIds = candidates.map(ProxyEntity::id)
        val existingIds = SagerDatabase.proxyDao.getAllIds().toHashSet()
        val liveIds = AutoSwitchPolicy.liveCandidateIds(candidateIds, existingIds).toHashSet()
        val testCandidates = candidates.filter { it.id in liveIds }
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
            if (changed.isNotEmpty()) SagerDatabase.proxyDao.updateProxy(changed.values.toList())
        }

        val bestId = AutoSwitchPolicy.best(results) ?: return
        if (bestId == DataStore.selectedProxy) return
        Logs.i("Auto switch waiting for active connections before selecting profile $bestId")
        while (scope.isActive && proxy.box.activeConnections() > 0L) {
            delay(CONNECTION_POLL_MS)
        }
        if (!scope.isActive) return
        // A subscription can be refreshed while the test or idle wait is running.
        // Never select an outbound that has already disappeared from the database.
        val selected = SagerDatabase.proxyDao.getById(bestId) ?: return
        if (!proxy.selectProfile(bestId)) return
        DataStore.selectedProxy = bestId
        DataStore.selectedGroup = selected.groupId
        proxy.displayProfileName = ServiceNotification.genTitle(selected)
        onSelected(selected)
        Logs.i("Auto switch selected profile $bestId (${results[bestId]}ms)")
    }

    companion object {
        private const val INITIAL_DELAY_MS = 30_000L
        private const val CONNECTION_POLL_MS = 5_000L
        private const val TEST_TIMEOUT_MS = 3_000
        private const val TEST_INTERVAL_MS = 10 * 60_000L
    }
}
