package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*

class TrafficLooper(val data: BaseService.Data) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private var job: Job? = null
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data
    private val lastBroadcastTraffic = mutableMapOf<Long, Pair<Long, Long>>()

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        persist()
        scope.cancel()
    }

    suspend fun persist() {
        if (!DataStore.profileTrafficStatistics) return
        val updates = synchronized(stateLock) {
            buildList {
                for (entities in data.proxy?.config?.trafficMap?.values.orEmpty()) {
                    for (ent in entities) {
                        val item = idMap[ent.id] ?: continue
                        ent.rx = item.rx
                        ent.tx = item.tx
                        add(ent)
                    }
                }
            }
        }
        val traffic = mutableMapOf<Long, TrafficData>()
        ProfileManager.updateProfile(updates)
        for (ent in updates) {
            traffic[ent.id] = TrafficData(id = ent.id, rx = ent.rx, tx = ent.tx)
        }
        data.binder.broadcast { b ->
            b.cbTrafficUpdateBatch(traffic.values.toList())
        }
        Logs.d("finally traffic post done")
    }

    fun start() {
        check(job == null) { "Traffic loop already started" }
        job = scope.launch { loop() }
    }

    private suspend fun loop() {
        val delayMs = DataStore.speedInterval.toLong()
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        if (delayMs == 0L) return

        var trafficUpdater: TrafficUpdater? = null
        var proxy: ProxyInstance?

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

        while (currentCoroutineContext().isActive) {
            proxy = data.proxy
            if (proxy == null) {
                delay(delayMs)
                continue
            }

            if (trafficUpdater == null) {
                if (!proxy.isInitialized()) {
                    delay(delayMs)
                    continue
                }
                synchronized(stateLock) {
                    idMap.clear()
                    tagMap.clear()
                    idMap[-1] = itemBypass
                    val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                    proxy.config.trafficMap.forEach { (tag, ents) ->
                        tags.add(tag)
                        for (ent in ents) {
                            val item = TrafficUpdater.TrafficLooperData(
                                tag = tag,
                                rx = ent.rx,
                                tx = ent.tx,
                                rxBase = ent.rx,
                                txBase = ent.tx,
                            )
                            idMap[ent.id] = item
                            tagMap[tag] = item
                            Logs.d("traffic count $tag to ${ent.id}")
                        }
                    }
                    trafficUpdater = TrafficUpdater(box = proxy.box, items = idMap.values.toList())
                    proxy.box.setV2rayStats(tags.joinToString("\n"))
                }
            }

            val hasForegroundClient = data.state == BaseService.State.Connected &&
                data.binder.callbackIdMap.containsValue(
                    SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
                )
            val (speed, trafficSnapshot) = synchronized(stateLock) {
                trafficUpdater!!.updateAll()
                var mainTxRate = 0L
                var mainRxRate = 0L
                var mainTx = 0L
                var mainRx = 0L
                tagMap.forEach { (_, item) ->
                    mainTxRate += item.txRate
                    mainRxRate += item.rxRate
                    mainTx += item.tx - item.txBase
                    mainRx += item.rx - item.rxBase
                }
                SpeedDisplayData(
                    mainTxRate,
                    mainRxRate,
                    if (showDirectSpeed) itemBypass.txRate else 0L,
                    if (showDirectSpeed) itemBypass.rxRate else 0L,
                    mainTx,
                    mainRx
                ) to if (hasForegroundClient && profileTrafficStatistics) {
                    idMap.mapNotNull { (id, item) ->
                        val current = item.tx to item.rx
                        if (lastBroadcastTraffic[id] == current) return@mapNotNull null
                        lastBroadcastTraffic[id] = current
                        TrafficData(id = id, rx = item.rx, tx = item.tx)
                    }
                } else emptyList()
            }
            currentCoroutineContext().ensureActive()

            // broadcast (MainActivity)
            if (hasForegroundClient) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        b.cbSpeedUpdate(speed)
                        if (profileTrafficStatistics) {
                            if (trafficSnapshot.isNotEmpty()) b.cbTrafficUpdateBatch(trafficSnapshot)
                        }
                    }
                }
            }

            // ServiceNotification
            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(speed)
            }

            delay(delayMs)
        }
    }
}
