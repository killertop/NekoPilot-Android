package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.core.ConnectionState
import io.nekohasekai.sagernet.core.ConnectionStateRepository
import io.nekohasekai.sagernet.core.ConnectionSessionGuard
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher

class SagerConnection(
    private var connectionId: Int,
    private var listenForDeath: Boolean = false
) : ServiceConnection, IBinder.DeathRecipient {

    companion object {
        val serviceClass get() = VpnService::class.java

        const val CONNECTION_ID_SHORTCUT = 0
        const val CONNECTION_ID_TILE = 1
        const val CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND = 2
        const val CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND = 3
        const val CONNECTION_ID_RESTART_BG = 4

        var restartingApp = false
    }

    interface Callback {
        // smaller ISagerNetServiceCallback

        fun stateChanged(state: ConnectionState, profileName: String?, msg: String?)

        fun missingPlugin(profileName: String, pluginName: String) {}

        fun onServiceConnected(service: ISagerNetService)

        /**
         * Different from Android framework, this method will be called even when you call `detachService`.
         */
        fun onServiceDisconnected() {}
        fun onBinderDied() {}
    }

    private val session = ConnectionSessionGuard()
    @Volatile
    private var callbackRegistered = false
    @Volatile
    private var callback: Callback? = null
    @Volatile
    private var serviceCallback: ISagerNetServiceCallback? = null

    private fun createServiceCallback(generation: Long) =
        object : ISagerNetServiceCallback.Stub() {
            override fun stateChanged(state: Int, profileName: String?, msg: String?) {
                if (state < 0) return // skip private
                val serviceState = ConnectionState.fromWireValue(state) ?: run {
                    Logs.w("Ignoring invalid connection state from service: $state")
                    return
                }
                val endpoint = localProxyEndpointFor(serviceState, service)
                dispatchToCurrentCallback(generation) { current ->
                    // Endpoint and state are one session-owned snapshot. A late Binder transaction
                    // must not mutate either half after disconnect/rebind.
                    ActiveLocalProxyEndpoint.snapshot = endpoint
                    ConnectionStateRepository.publish(this@SagerConnection, serviceState)
                    current.stateChanged(serviceState, profileName, msg)
                }
            }

            override fun missingPlugin(profileName: String, pluginName: String) {
                dispatchToCurrentCallback(generation) { current ->
                    current.missingPlugin(profileName, pluginName)
                }
            }
        }

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    var service: ISagerNetService? = null

    fun updateConnectionId(id: Int) {
        connectionId = id
        try {
            serviceCallback?.let { service?.registerCallback(it, id) }
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        val service = ISagerNetService.Stub.asInterface(binder)!!
        val generation = session.currentGeneration
        val serviceCallback = createServiceCallback(generation)
        if (!session.commitIfCurrent(generation) {
                this.binder = binder
                this.service = service
                this.serviceCallback = serviceCallback
            }
        ) {
            return
        }
        var deathLinked = false
        var registered = false
        try {
            if (listenForDeath) {
                binder.linkToDeath(this, 0)
                deathLinked = true
            }
            service.registerCallback(serviceCallback, connectionId)
            registered = true
            val initialState = ConnectionState.fromWireValue(service.state)
            val endpoint = initialState?.let { localProxyEndpointFor(it, service) }
            val accepted = session.commitIfCurrent(generation) {
                // disconnect() may have won while the remote registration was in flight.
                if (this.serviceCallback !== serviceCallback) return@commitIfCurrent
                callbackRegistered = true
                if (initialState == null) {
                    ActiveLocalProxyEndpoint.snapshot = null
                    ConnectionStateRepository.markDead(this)
                } else {
                    ActiveLocalProxyEndpoint.snapshot = endpoint
                    ConnectionStateRepository.publish(this, initialState)
                }
            }
            if (!accepted || this.serviceCallback !== serviceCallback) {
                cleanUpStaleRegistration(service, serviceCallback, binder, registered, deathLinked)
                return
            }
            if (initialState == null) Logs.w("Ignoring invalid initial connection state from service")
        } catch (e: RemoteException) {
            Logs.w(e)
            cleanUpStaleRegistration(service, serviceCallback, binder, registered, deathLinked)
            session.commitIfCurrent(generation) {
                callbackRegistered = false
                if (this.serviceCallback === serviceCallback) this.serviceCallback = null
                ActiveLocalProxyEndpoint.snapshot = null
                ConnectionStateRepository.markDead(this)
            }
        }
        val target = callback
        if (target != null) {
            session.commitIfCurrent(generation) {
                if (callback === target) target.onServiceConnected(service)
            }
        }
    }

    private fun cleanUpStaleRegistration(
        service: ISagerNetService,
        serviceCallback: ISagerNetServiceCallback,
        binder: IBinder,
        registered: Boolean,
        deathLinked: Boolean,
    ) {
        if (registered) runCatching { service.unregisterCallback(serviceCallback) }
        if (deathLinked) runCatching { binder.unlinkToDeath(this, 0) }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        val generation = session.advance()
        unregisterCallback()
        session.commitIfCurrent(generation) {
            ConnectionStateRepository.markDead(this)
            callback?.onServiceDisconnected()
            service = null
            binder = null
            ActiveLocalProxyEndpoint.snapshot = null
        }
    }

    override fun binderDied() {
        val generation = session.advance()
        session.commitIfCurrent(generation) {
            service = null
            callbackRegistered = false
            serviceCallback = null
            ConnectionStateRepository.markDead(this)
            ActiveLocalProxyEndpoint.snapshot = null
        }
        if (!restartingApp) {
            dispatchToCurrentCallback(generation) { current ->
                if (!restartingApp) current.onBinderDied()
            }
        }
    }

    private fun localProxyEndpointFor(
        state: ConnectionState,
        service: ISagerNetService?,
    ) = if (state.connected) {
        runCatching {
            service?.localProxyEndpoint?.toLocalProxyEndpoint()
        }.getOrNull()
    } else null

    private fun dispatchToCurrentCallback(
        generation: Long,
        action: (Callback) -> Unit,
    ) {
        val target = callback ?: return
        runOnMainDispatcher {
            // Binder events can already be queued when an Activity disconnects or is recreated.
            // Validate identity and generation before every session-owned mutation.
            session.commitIfCurrent(generation) {
                if (callback === target) {
                    action(target)
                }
            }
        }
    }

    private fun unregisterCallback() {
        val service = service
        val serviceCallback = serviceCallback
        if (service != null && serviceCallback != null && callbackRegistered) try {
            service.unregisterCallback(serviceCallback)
        } catch (_: RemoteException) {
        }
        callbackRegistered = false
        this.serviceCallback = null
    }

    fun connect(context: Context, callback: Callback?) {
        val generation = session.begin() ?: return
        check(this.callback == null)
        this.callback = callback
        ConnectionStateRepository.beginBinding(this)
        val intent = Intent(context, serviceClass).setAction(Action.SERVICE)
        val bound = try {
            context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        } catch (e: RuntimeException) {
            Logs.w(e)
            false
        }
        if (!bound) session.fail(generation) {
            // Dead is intentionally not Bound(Idle): without a Binder snapshot the VPN may still
            // be running in :bg, so enabling "start" here could launch it twice.
            this.callback = null
            ConnectionStateRepository.markDead(this)
        }
    }

    fun disconnect(context: Context) {
        val wasActive = session.isActive
        val boundBinder = binder
        unregisterCallback()
        session.close {
            callback = null
            binder = null
            service = null
            ActiveLocalProxyEndpoint.snapshot = null
            ConnectionStateRepository.remove(this)
        }
        if (wasActive) try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
        }   // ignore
        if (listenForDeath) try {
            boundBinder?.unlinkToDeath(this, 0)
        } catch (_: NoSuchElementException) {
        }
    }
}
