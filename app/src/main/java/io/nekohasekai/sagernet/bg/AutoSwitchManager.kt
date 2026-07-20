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
}

/**
 * Tests the runtime selector's complete profile set with one disposable core.
 * The selected outbound is changed only after the main core has no live TCP
 * connections, so a long-lived stream is never reset by automatic switching.
 */
class AutoSwitchManager(
    private val scope: CoroutineScope,
    private val proxy: ProxyInstance,
    private val candidates: List<ProxyEntity>,
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
        val results = linkedMapOf<Long, Int>()
        val changed = linkedMapOf<Long, ProxyEntity>()
        val runner = TestInstance(
            profile = candidates.first(),
            link = CONNECTION_TEST_URL,
            timeout = TEST_TIMEOUT_MS,
            testProfiles = candidates,
            downloadEnabled = false,
        )
        try {
            runner.runBatch(
                candidates,
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
        Logs.i("Auto switch waiting for active TCP connections before selecting profile $bestId")
        while (scope.isActive && proxy.box.activeTCPConnections() > 0L) {
            delay(CONNECTION_POLL_MS)
        }
        if (!scope.isActive || !proxy.selectProfile(bestId)) return
        val selected = candidates.firstOrNull { it.id == bestId } ?: return
        DataStore.selectedProxy = bestId
        DataStore.selectedGroup = selected.groupId
        proxy.displayProfileName = ServiceNotification.genTitle(selected)
        Logs.i("Auto switch selected profile $bestId (${results[bestId]}ms)")
    }

    companion object {
        private const val INITIAL_DELAY_MS = 30_000L
        private const val CONNECTION_POLL_MS = 5_000L
        private const val TEST_TIMEOUT_MS = 3_000
        private const val TEST_INTERVAL_MS = 10 * 60_000L
    }
}
