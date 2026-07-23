package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.core.ConnectionState
import io.nekohasekai.sagernet.core.ConnectionStateRepository
import io.nekohasekai.sagernet.core.ConnectionStopResult
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util

internal fun connectionSnapshotMatches(
    started: ProxyEntity,
    selectedProfileId: Long,
    persisted: ProxyEntity?,
): Boolean = persisted != null &&
    selectedProfileId == started.id && persisted.id == started.id &&
    persisted.groupId == started.groupId && persisted.type == started.type &&
    persisted.configRevision == started.configRevision

class BaseService {

    interface ExpectedException

    class Data internal constructor(internal val service: Interface) {
        internal val lifecycle = ServiceLifecycle()
        private val stateMachine = ConnectionStateMachine()
        val state: ConnectionState get() = stateMachine.state
        @Volatile
        var profile: ProxyEntity? = null
        @Volatile
        var attemptedProfileId: Long = 0L
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> Unit
                Action.RELOAD -> lifecycle.scope.launch(Dispatchers.IO) {
                    // The UI process flushes before sending RELOAD. Refresh this process'
                    // Room-backed cache before rebuilding the VPN application allow list.
                    DataStore.configurationStore.refresh()
                    service.reload()
                }
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    // BroadcastReceiver runs on the main thread. pause/wake/reset can wait for a
                    // native reload's operation lock, so coalesce the latest idle state onto the
                    // service IO scope instead of risking a broadcast ANR.
                    requestDeviceIdleModeChange(SagerNet.power.isDeviceIdleMode)
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> lifecycle.scope.launch {
                    service.resetCoreNetwork()
                    withContext(Dispatchers.Main.immediate) {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, R.string.reset_connections_done, Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        private val deviceIdleModeChanges = Channel<Boolean>(Channel.CONFLATED)
        private val deviceIdleModeJob = lifecycle.scope.launch(Dispatchers.IO) {
            for (idle in deviceIdleModeChanges) {
                if (idle) {
                    service.pauseCore()
                } else {
                    service.wakeCore()
                    service.resetCoreNetwork()
                }
            }
        }

        private fun requestDeviceIdleModeChange(idle: Boolean) {
            deviceIdleModeChanges.trySend(idle)
        }

        // Subscription updates and UI edits can broadcast several reloads while an earlier
        // native rebuild is still running. The active rebuild must finish, but only the newest
        // persisted configuration matters afterwards; never queue an unbounded series of full
        // libbox/TUN rebuilds.
        private val reloadLock = Any()
        private var reloadRequested = false
        private var reloadJob: Job? = null

        fun requestReload(block: suspend () -> Unit) {
            var jobToStart: Job? = null
            synchronized(reloadLock) {
                if (lifecycle.destroyed) return
                reloadRequested = true
                if (reloadJob != null) return
                lateinit var job: Job
                job = lifecycle.scope.launch(
                    context = Dispatchers.Main.immediate,
                    start = CoroutineStart.LAZY,
                ) {
                    try {
                        while (true) {
                            val shouldRun = synchronized(reloadLock) {
                                if (!reloadRequested) false else {
                                    reloadRequested = false
                                    true
                                }
                            }
                            if (!shouldRun) return@launch
                            block()
                        }
                    } finally {
                        val restart = synchronized(reloadLock) {
                            if (reloadJob !== job) return@synchronized false
                            reloadJob = null
                            reloadRequested
                        }
                        // A request can land after the loop observes an empty queue but before
                        // this job clears its identity. Start one fresh worker for that final
                        // request instead of losing it.
                        if (restart) requestReload(block)
                    }
                }
                reloadJob = job
                jobToStart = job
            }
            jobToStart?.start()
        }

        val binder = Binder(this)
        var connectingJob: Job? = null

        private fun publishState(msg: String? = null) {
            val current = state
            ConnectionStateRepository.publish(this, current)
            binder.stateChanged(current, msg)
        }

        fun beginStart(): Boolean = stateMachine.beginStart().also { changed ->
            if (changed) publishState()
        }

        fun markConnecting(): Boolean = stateMachine.markConnecting().also { changed ->
            if (changed) publishState()
        }

        fun commitConnected(lateInit: () -> Unit): Boolean {
            var changed = false
            val accepted = lifecycle.commitIfAlive {
                changed = stateMachine.markConnected()
                if (changed) {
                    publishState()
                    lateInit()
                }
            }
            return accepted && changed
        }

        fun beginStop(restart: Boolean): Boolean {
            val decision = stateMachine.requestStop(restart)
            if (decision.stateChanged) publishState()
            return decision.shouldStop
        }

        fun finishStop(result: ConnectionStopResult): Boolean {
            val completion = stateMachine.finishStop(result)
            publishState((result as? ConnectionStopResult.Failed)?.message)
            return completion.shouldRestart
        }

        fun failPreparing(msg: String) {
            if (stateMachine.failPreparing()) publishState(msg)
        }
    }

    class Binder(@Volatile private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: ConnectionState.Idle).wireValue
        override fun getProfileName(): String = data?.profile?.let(ServiceNotification::genTitle) ?: "Idle"
        override fun getLocalProxyEndpoint(): Bundle? =
            data?.service?.localProxyEndpoint()?.toBundle()
        override fun getTrafficSnapshot(): Bundle? = data?.service?.trafficSnapshot()

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            // RemoteCallbackList already de-duplicates binder identities. Keeping a
            // second mutable map here added a Binder-thread race without affecting
            // delivery, because the connection id is only used by the restart request.
            callbacks.register(cb)
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbacks.unregister(cb)
        }

        override fun urlTest(): Int {
            return data?.service?.urlTest() ?: error("core not started")
        }

        override fun protectSocket(socket: ParcelFileDescriptor?): Boolean {
            val descriptor = socket ?: return false
            return try {
                val vpnService = data?.service as? VpnService ?: return false
                vpnService.protect(descriptor.fd)
            } finally {
                descriptor.close()
            }
        }

        override fun selectProfile(profileId: Long): Boolean =
            data?.service?.selectProfile(profileId) == true

        override fun setAutomaticNodeSwitchingEnabled(enabled: Boolean): Boolean =
            data?.service?.setAutomaticNodeSwitchingEnabled(enabled) == true

        fun stateChanged(s: ConnectionState, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.wireValue, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification
        fun startupError(): String? = null

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            data.requestReload {
                val s = data.state
                when {
                    s.canStart -> {
                        if (DataStore.selectedProxy == 0L) {
                            stopRunner(
                                false,
                                (this@Interface as Context).getString(R.string.profile_empty),
                            )
                        } else {
                            startRunner()
                        }
                    }
                    s == ConnectionState.Connected -> {
                        try {
                            withContext(Dispatchers.IO) { reloadCore() }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            // A rejected candidate is not a connection failure. The running
                            // service remains authoritative and must stay published as Connected.
                            Logs.w("VPN candidate reload rejected; keeping the running core", error)
                        }
                    }
                    s == ConnectionState.Preparing || s == ConnectionState.Connecting ->
                        stopRunner(true)
                    s == ConnectionState.Stopping -> stopRunner(true)
                }
            }
        }

        /**
         * Starts an immutable profile snapshot and verifies it again after libbox has bound all
         * resources. Subscription updates and user selection live in another process and can land
         * while startCore() is running; a mismatched snapshot is closed and rebuilt before the
         * service is ever published as connected.
         */
        suspend fun startProcessesWithValidation(initialProfile: ProxyEntity): ProxyEntity {
            var candidate = initialProfile
            repeat(MAX_PROFILE_START_ATTEMPTS) {
                val beforeStart = withContext(Dispatchers.IO) {
                    val selection = DataStore.readProxySelection()
                    val persisted = SagerDatabase.proxyDao.getById(selection.profileId)
                    if (connectionSnapshotMatches(candidate, selection.profileId, persisted)) candidate
                    else persisted ?: ProfileManager.ensureValidSelection()
                } ?: error((this as Context).getString(R.string.profile_empty))

                candidate = beforeStart
                data.attemptedProfileId = candidate.id
                data.profile = candidate
                data.notification?.postNotificationTitle(ServiceNotification.genTitle(candidate))
                withContext(Dispatchers.IO) {
                    DataStore.currentProfile = candidate.id
                    DataStore.configurationStore.flush()
                }

                startCore(candidate)
                val current = withContext(Dispatchers.IO) {
                    val selection = DataStore.readProxySelection()
                    selection.profileId to SagerDatabase.proxyDao.getById(selection.profileId)
                }
                if (connectionSnapshotMatches(candidate, current.first, current.second)) {
                    return candidate
                }

                Logs.w("Connection profile changed during startup; rebuilding the core")
                stopCore()
                candidate = current.second ?: candidate
            }
            error("Connection profile kept changing during startup")
        }

        suspend fun startCore(profile: ProxyEntity)
        suspend fun reloadCore()
        suspend fun stopCore()
        fun pauseCore()
        fun wakeCore()
        fun resetCoreNetwork()
        fun urlTest(): Int
        fun localProxyEndpoint(): DataStore.LocalProxyEndpoint? = null
        fun trafficSnapshot(): Bundle? = null
        fun selectProfile(profileId: Long): Boolean = false
        fun setAutomaticNodeSwitchingEnabled(enabled: Boolean): Boolean = false

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        suspend fun killProcesses() {
            var cleanupFailure: Throwable? = null
            try {
                stopCore()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                cleanupFailure = error
                Logs.w("Proxy cleanup failed (${error.javaClass.simpleName})")
            }
            wakeLock?.let { lock ->
                runCatching {
                    if (lock.isHeld) lock.release()
                }.onFailure { error ->
                    Logs.w("Wake lock cleanup failed (${error.javaClass.simpleName})")
                }
                wakeLock = null
            }
            // Await removal before a reload starts preInit() again. A fire-and-forget stop could
            // arrive after the new start and remove the freshly registered listener.
            try {
                DefaultNetworkListener.stop(this)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Logs.w("Network listener cleanup failed (${error.javaClass.simpleName})")
            } finally {
                SagerNet.underlyingNetwork = null
                upstreamInterfaceName = null
            }
            cleanupFailure?.let { throw it }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            val shouldStop = data.beginStop(restart)
            if (!shouldStop) return
            data.notification?.destroy()
            data.notification = null
            this as Service
            val requestedFailure = msg?.let(Protocols::genFriendlyMsg)?.take(500)
            val failedProfileId = data.attemptedProfileId

            data.lifecycle.scope.launch(Dispatchers.Main.immediate) {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                var teardownFailure: Throwable? = null
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    withContext(Dispatchers.IO) {
                        try {
                            killProcesses()
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            teardownFailure = error
                        }
                    }
                    ServiceRuntimeRegistry.unregisterBase(this@Interface)
                    (this@Interface as? VpnService)?.let(ServiceRuntimeRegistry::unregisterVpn)
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    data.profile = null
                }
                val friendlyFailure = requestedFailure ?: teardownFailure
                    ?.let { Protocols.genFriendlyMsg(it.readableMessage) }
                    ?.take(500)

                if (friendlyFailure != null) {
                    withContext(Dispatchers.IO) {
                        DataStore.lastConnectionError = friendlyFailure
                        DataStore.lastConnectionErrorProfile = failedProfileId
                        DataStore.lastConnectionErrorTime = System.currentTimeMillis()
                        DataStore.configurationStore.flush()
                    }
                }

                val shouldRestart = data.finishStop(
                    friendlyFailure?.let(ConnectionStopResult::Failed)
                        ?: ConnectionStopResult.Completed,
                )
                // stop the service if nothing has bound to it
                if (shouldRestart) startRunner() else {
                    stopSelf()
                }
            }
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            DefaultNetworkListener.start(this) listener@{ network ->
                // Lost/fallback-null is a real state transition. Clear the old Android Network
                // immediately so the VPN cannot stay pinned to a dead Wi-Fi/cellular handle.
                SagerNet.underlyingNetwork = network
                ServiceRuntimeRegistry.vpnService?.updateUnderlyingNetwork()
                if (network == null) {
                    upstreamInterfaceName = null
                    return@listener
                }
                val link = SagerNet.connectivity.getLinkProperties(network) ?: run {
                    upstreamInterfaceName = null
                    return@listener
                }
                val oldName = upstreamInterfaceName
                if (oldName != link.interfaceName) {
                    upstreamInterfaceName = link.interfaceName
                }
                if (
                    oldName != null && upstreamInterfaceName != null &&
                    oldName != upstreamInterfaceName
                ) {
                    Logs.d("Network changed: $oldName -> $upstreamInterfaceName")
                    resetCoreNetwork()
                }
            }
            // Listener registration is asynchronous on modern Android. Seed the platform with
            // the current non-VPN network before libbox starts, otherwise the TUN can be created
            // while every outbound still reports "no available network interface".
            if (SagerNet.underlyingNetwork == null) {
                SagerNet.connectivity.activeNetwork?.let { network ->
                    SagerNet.underlyingNetwork = network
                    upstreamInterfaceName = SagerNet.connectivity
                        .getLinkProperties(network)
                        ?.interfaceName
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            acquireWakeLock()
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            ServiceRuntimeRegistry.registerBase(this)

            val data = data
            if (!data.beginStart()) return Service.START_NOT_STICKY
            this as Context
            data.attemptedProfileId = 0L
            try {
                // A foreground service must promote itself immediately. Profile/database work
                // happens only after Android has accepted the ongoing VPN notification.
                data.notification = createNotification(getString(R.string.app_name))
            } catch (error: Throwable) {
                Logs.e("Unable to enter VPN foreground service", error)
                data.failPreparing(error.readableMessage)
                ServiceRuntimeRegistry.unregisterBase(this)
                (this as Service).stopSelf(startId)
                return Service.START_NOT_STICKY
            }
            startupError()?.let { error ->
                stopRunner(false, error)
                return Service.START_NOT_STICKY
            }
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                ContextCompat.registerReceiver(
                    this@Interface,
                    data.receiver,
                    filter,
                    "$packageName.permission.SERVICE_CONTROL",
                    null,
                    ContextCompat.RECEIVER_EXPORTED,
                )
                data.closeReceiverRegistered = true
            }
            data.connectingJob = data.lifecycle.scope.launch connect@{
                try {
                    // The VPN runs in another process. Refresh the shared preference cache before
                    // reading the selected node, otherwise the first connection after an import
                    // can start with the previous (often empty) selection.
                    val profile = withContext(Dispatchers.IO) {
                        ProfileManager.ensureValidSelection()
                    }
                    if (profile == null) {
                        stopRunner(false, getString(R.string.profile_empty))
                        return@connect
                    }
                    data.attemptedProfileId = profile.id
                    data.profile = profile
                    data.notification?.postNotificationTitle(ServiceNotification.genTitle(profile))

                    if (!data.markConnecting()) return@connect
                    preInit()
                    withContext(Dispatchers.IO) {
                        DataStore.lastConnectionError = ""
                        DataStore.lastConnectionErrorProfile = 0L
                        DataStore.lastConnectionErrorTime = 0L
                        DataStore.configurationStore.flush()
                    }

                    startProcessesWithValidation(profile)
                    if (!data.commitConnected(::lateInit)) {
                        // A stop/reload won while core startup was completing. The stop owner will
                        // close this core; never publish a stale Connected state or acquire a
                        // WakeLock after Service destruction.
                        return@connect
                    }
                    data.notification?.postNotificationWakeLockStatus(true)
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }

        private companion object {
            const val MAX_PROFILE_START_ATTEMPTS = 3
        }
    }

}
