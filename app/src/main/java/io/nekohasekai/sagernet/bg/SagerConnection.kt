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
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import java.util.concurrent.atomic.AtomicLong

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

    @Volatile
    private var connectionActive = false
    private val callbackGeneration = AtomicLong()
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
                if (serviceState.connected) {
                    refreshLocalProxyEndpoint(service)
                } else {
                    ActiveLocalProxyEndpoint.snapshot = null
                }
                dispatchToCurrentCallback(generation) { current ->
                    // Keep the shared state behind the same session check as the UI callback.
                    // A late Binder transaction from a dead service must not revive Connected.
                    DataStore.serviceState = serviceState
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
        this.binder = binder
        val service = ISagerNetService.Stub.asInterface(binder)!!
        this.service = service
        val generation = callbackGeneration.get()
        val serviceCallback = createServiceCallback(generation).also {
            this.serviceCallback = it
        }
        try {
            if (listenForDeath) binder.linkToDeath(this, 0)
            check(!callbackRegistered)
            service.registerCallback(serviceCallback, connectionId)
            callbackRegistered = true
            if (ConnectionState.fromWireValue(service.state)?.connected == true) {
                refreshLocalProxyEndpoint(service)
            } else {
                ActiveLocalProxyEndpoint.snapshot = null
            }
        } catch (e: RemoteException) {
            Logs.w(e)
        }
        callback?.takeIf { connectionActive }?.onServiceConnected(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        callbackGeneration.incrementAndGet()
        unregisterCallback()
        callback?.takeIf { connectionActive }?.onServiceDisconnected()
        service = null
        binder = null
        ActiveLocalProxyEndpoint.snapshot = null
    }

    override fun binderDied() {
        callbackGeneration.incrementAndGet()
        service = null
        callbackRegistered = false
        serviceCallback = null
        ActiveLocalProxyEndpoint.snapshot = null
        if (!restartingApp) {
            dispatchToCurrentCallback(callbackGeneration.get()) { current ->
                if (!restartingApp) current.onBinderDied()
            }
        }
    }

    private fun refreshLocalProxyEndpoint(service: ISagerNetService?) {
        ActiveLocalProxyEndpoint.snapshot = runCatching {
            service?.localProxyEndpoint?.toLocalProxyEndpoint()
        }.getOrNull()
    }

    private fun dispatchToCurrentCallback(
        generation: Long,
        action: (Callback) -> Unit,
    ) {
        val target = callback ?: return
        runOnMainDispatcher {
            // Binder events can already be queued when an Activity disconnects or is recreated.
            // Validate both identity and generation at delivery time so an old screen is never
            // touched, even when the same callback object is later reused.
            if (
                connectionActive &&
                callbackGeneration.get() == generation &&
                callback === target
            ) {
                action(target)
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
        if (connectionActive) return
        connectionActive = true
        check(this.callback == null)
        callbackGeneration.incrementAndGet()
        this.callback = callback
        val intent = Intent(context, serviceClass).setAction(Action.SERVICE)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun disconnect(context: Context) {
        callbackGeneration.incrementAndGet()
        unregisterCallback()
        if (connectionActive) try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
        }   // ignore
        connectionActive = false
        if (listenForDeath) try {
            binder?.unlinkToDeath(this, 0)
        } catch (_: NoSuchElementException) {
        }
        binder = null
        service = null
        callback = null
    }
}
