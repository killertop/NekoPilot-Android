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
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.*

class TrafficLooper(val data: BaseService.Data) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private var job: Job? = null
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        persist()
        scope.cancel()
    }

    suspend fun persist() {
        if (!DataStore.profileTrafficStatistics) return
        val updates = synchronized(stateLock) {
            data.proxy?.config?.trafficMap?.values.orEmpty().flatten().mapNotNull { ent ->
                val item = idMap[ent.id] ?: return@mapNotNull null
                ent.rx = item.rx
                ent.tx = item.tx
                ent
            }
        }
        val traffic = mutableMapOf<Long, TrafficData>()
        for (ent in updates) {
            ProfileManager.updateProfile(ent)
            traffic[ent.id] = TrafficData(id = ent.id, rx = ent.rx, tx = ent.tx)
        }
        data.binder.broadcast { b ->
            for (t in traffic) {
                b.cbTrafficUpdate(t.value)
            }
        }
        Logs.d("finally traffic post done")
    }

    fun start() {
        check(job == null) { "Traffic loop already started" }
        job = scope.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    fun selectMain(id: Long) {
        synchronized(stateLock) {
            selectMainLocked(id)
        }
    }

    private fun selectMainLocked(id: Long) {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // post traffic when switch
            if (DataStore.profileTrafficStatistics) {
                data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    runOnDefaultDispatcher {
                        ProfileManager.updateProfile(it) // update DB
                    }
                }
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }
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
                                ignore = proxy.config.selectorGroupId >= 0L,
                            )
                            idMap[ent.id] = item
                            tagMap[tag] = item
                            Logs.d("traffic count $tag to ${ent.id}")
                        }
                    }
                    if (proxy.config.selectorGroupId >= 0L) {
                        selectMainLocked(proxy.config.mainEntId)
                    }
                    trafficUpdater = TrafficUpdater(box = proxy.box, items = idMap.values.toList())
                    proxy.box.setV2rayStats(tags.joinToString("\n"))
                }
            }

            val (speed, trafficSnapshot) = synchronized(stateLock) {
                trafficUpdater!!.updateAll()
                var mainTxRate = 0L
                var mainRxRate = 0L
                var mainTx = 0L
                var mainRx = 0L
                tagMap.forEach { (_, item) ->
                    if (!item.ignore) {
                        mainTxRate += item.txRate
                        mainRxRate += item.rxRate
                    }
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
                ) to idMap.map { (id, item) -> TrafficData(id = id, rx = item.rx, tx = item.tx) }
            }
            currentCoroutineContext().ensureActive()

            // broadcast (MainActivity)
            if (data.state == BaseService.State.Connected
                && data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        b.cbSpeedUpdate(speed)
                        if (profileTrafficStatistics) {
                            trafficSnapshot.forEach(b::cbTrafficUpdate)
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
