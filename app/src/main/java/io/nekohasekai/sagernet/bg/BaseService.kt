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
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(internal val service: Interface) {
        @Volatile
        var state = State.Stopped
        @Volatile
        var profile: ProxyEntity? = null
        @Volatile
        var attemptedProfileId: Long = 0L
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> Unit
                Action.RELOAD -> runOnIoDispatcher {
                    // The UI process flushes before sending RELOAD. Refresh this process'
                    // Room-backed cache before rebuilding the VPN application allow list.
                    DataStore.configurationStore.refreshBlocking()
                    onMainDispatcher { service.reload() }
                }
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (SagerNet.power.isDeviceIdleMode) {
                        service.pauseCore()
                    } else {
                        service.wakeCore()
                        service.resetCoreNetwork()
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    service.resetCoreNetwork()
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, R.string.reset_connections_done, Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
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

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.profile?.let(ServiceNotification::genTitle) ?: "Idle"
        override fun getLocalProxyEndpoint(): Bundle? =
            data?.service?.localProxyEndpoint()?.toBundle()

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

        override fun requestNodeTest(): Boolean =
            data?.service?.requestNodeTest() == true

        override fun selectProfile(profileId: Long): Boolean =
            data?.service?.selectProfile(profileId) == true

        override fun setAutomaticNodeSelectionEnabled(enabled: Boolean): Boolean =
            data?.service?.setAutomaticNodeSelectionEnabled(enabled) == true

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
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

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            runOnDefaultDispatcher {
                onMainDispatcher {
                    val s = data.state
                    when {
                        s == State.Stopped -> startRunner()
                        s.canStop -> stopRunner(true)
                        else -> Logs.w("Illegal state $s when invoking use")
                    }
                }
            }
        }

        suspend fun startProcesses() = startCore(requireNotNull(data.profile))

        suspend fun startCore(profile: ProxyEntity)
        suspend fun stopCore()
        fun pauseCore()
        fun wakeCore()
        fun resetCoreNetwork()
        fun urlTest(): Int
        fun requestNodeTest(): Boolean = false
        fun localProxyEndpoint(): DataStore.LocalProxyEndpoint? = null
        fun selectProfile(profileId: Long): Boolean = false
        fun setAutomaticNodeSelectionEnabled(enabled: Boolean): Boolean = false

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        suspend fun killProcesses() {
            runCatching { stopCore() }.onFailure { error ->
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
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            DataStore.baseService = null
            DataStore.vpnService = null

            val shouldStop = synchronized(data) {
                if (data.state == State.Stopping) {
                    false
                } else {
                    data.notification?.destroy()
                    data.notification = null
                    data.changeState(State.Stopping)
                    true
                }
            }
            if (!shouldStop) return
            this as Service
            val friendlyFailure = msg?.let(Protocols::genFriendlyMsg)?.take(500)
            val failedProfileId = data.attemptedProfileId

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    withContext(Dispatchers.IO) {
                        killProcesses()
                    }
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    data.profile = null
                }

                if (friendlyFailure != null) {
                    withContext(Dispatchers.IO) {
                        DataStore.lastConnectionError = friendlyFailure
                        DataStore.lastConnectionErrorProfile = failedProfileId
                        DataStore.lastConnectionErrorTime = System.currentTimeMillis()
                        DataStore.configurationStore.flushBlocking()
                    }
                }

                // change the state
                data.changeState(State.Stopped, friendlyFailure)
                // stop the service if nothing has bound to it
                if (restart) startRunner() else {
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
                DataStore.vpnService?.updateUnderlyingNetwork()
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

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            acquireWakeLock()
            data.notification?.postNotificationWakeLockStatus(true)
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            this as Context
            data.attemptedProfileId = 0L
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
            data.changeState(State.Connecting)
            data.connectingJob = runOnDefaultDispatcher {
                try {
                    // The VPN runs in another process. Refresh the shared preference cache before
                    // reading the selected node, otherwise the first connection after an import
                    // can start with the previous (often empty) selection.
                    withContext(Dispatchers.IO) {
                        DataStore.configurationStore.refreshBlocking()
                    }
                    val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                    if (profile == null) {
                        data.notification = createNotification("")
                        stopRunner(false, getString(R.string.profile_empty))
                        return@runOnDefaultDispatcher
                    }
                    data.attemptedProfileId = profile.id
                    data.profile = profile
                    data.notification = createNotification(ServiceNotification.genTitle(profile))

                    preInit()
                    DataStore.currentProfile = profile.id
                    withContext(Dispatchers.IO) {
                        DataStore.lastConnectionError = ""
                        DataStore.lastConnectionErrorProfile = 0L
                        DataStore.lastConnectionErrorTime = 0L
                        DataStore.configurationStore.flushBlocking()
                    }

                    startProcesses()
                    data.changeState(State.Connected)

                    lateInit()
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
    }

}
