package io.nekohasekai.sagernet.utils

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import java.net.UnknownHostException

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage() {
            val completed = CompletableDeferred<Unit>()
        }

        class Put(val generation: Long, val network: Network) : NetworkMessage()
        class Update(val generation: Long, val network: Network) : NetworkMessage()
        class Lost(val generation: Long, val network: Network) : NetworkMessage()
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private val networkActor = applicationScope.actor<NetworkMessage>(
        context = Dispatchers.Unconfined,
        capacity = Channel.UNLIMITED,
    ) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null
        val pendingRequests = arrayListOf<NetworkMessage.Get>()
        fun notifyListener(listener: (Network?) -> Unit, value: Network?) {
            try {
                listener(value)
            } catch (error: Throwable) {
                runCatching {
                    Logs.w("Default network listener failed (${error.javaClass.simpleName})")
                }
            }
        }
        fun notifyListeners(value: Network?) {
            listeners.values.toList().forEach { notifyListener(it, value) }
        }
        for (message in channel) when (message) {
            is NetworkMessage.Start -> {
                if (listeners.isEmpty()) register()
                listeners[message.key] = message.listener
                val current = if (fallback) {
                    SagerNet.connectivity.activeNetwork
                } else {
                    network
                }
                if (current != null || fallback) notifyListener(message.listener, current)
            }
            is NetworkMessage.Get -> {
                if (listeners.isEmpty()) {
                    message.response.completeExceptionally(UnknownHostException())
                } else if (fallback) {
                    val active = SagerNet.connectivity.activeNetwork
                    if (active == null) {
                        message.response.completeExceptionally(UnknownHostException())
                    } else {
                        message.response.complete(active)
                    }
                } else if (network == null) {
                    pendingRequests += message
                } else {
                    message.response.complete(network)
                }
            }
            is NetworkMessage.Stop -> {
                if (listeners.isNotEmpty() && // was not empty
                    listeners.remove(message.key) != null && listeners.isEmpty()
                ) {
                    network = null
                    pendingRequests.forEach {
                        it.response.completeExceptionally(UnknownHostException())
                    }
                    pendingRequests.clear()
                    unregister()
                }
                message.completed.complete(Unit)
            }

            is NetworkMessage.Put -> {
                if (!message.isFromActiveRegistration(listeners)) continue
                network = message.network
                pendingRequests.forEach { it.response.complete(message.network) }
                pendingRequests.clear()
                notifyListeners(network)
            }
            is NetworkMessage.Update -> if (
                message.isFromActiveRegistration(listeners) && network == message.network
            ) notifyListeners(network)
            is NetworkMessage.Lost -> if (network == message.network) {
                if (!message.isFromActiveRegistration(listeners)) continue
                network = null
                notifyListeners(null)
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) =
        networkActor.send(NetworkMessage.Start(key, listener))

    suspend fun get() = NetworkMessage.Get().run {
        networkActor.send(this)
        response.await()
    }

    suspend fun stop(key: Any) = NetworkMessage.Stop(key).run {
        networkActor.send(this)
        completed.await()
    }

    /** Queues cleanup for synchronous Android lifecycle callbacks that cannot suspend. */
    fun requestStop(key: Any) {
        networkActor.trySend(NetworkMessage.Stop(key))
    }

    private fun NetworkMessage.isFromActiveRegistration(
        listeners: Map<Any, (Network?) -> Unit>,
    ): Boolean {
        val generation = when (this) {
            is NetworkMessage.Put -> generation
            is NetworkMessage.Update -> generation
            is NetworkMessage.Lost -> generation
            else -> return false
        }
        return listeners.isNotEmpty() && callbackRegistered &&
            generation == activeRegistrationGeneration
    }

    // NB: these run in ConnectivityThread, and this behavior cannot be changed until API 26.
    // A fresh callback object gives every registration a stable generation, so queued events
    // from an unregistered callback cannot contaminate a later VPN reload.
    private fun createCallback(generation: Long) =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) =
                networkActor.trySend(NetworkMessage.Put(generation, network)).let { Unit }

            override fun onCapabilitiesChanged(
                network: Network, networkCapabilities: NetworkCapabilities
            ) { // it's a good idea to refresh capabilities
                networkActor.trySend(NetworkMessage.Update(generation, network)).let { Unit }
            }

            override fun onLost(network: Network) =
                networkActor.trySend(NetworkMessage.Lost(generation, network)).let { Unit }
        }

    private var fallback = false
    private var registrationGeneration = 0L
    @Volatile
    private var activeRegistrationGeneration = 0L
    private var callbackRegistered = false
    private var registeredCallback: ConnectivityManager.NetworkCallback? = null
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        // The upstream monitor must never select NekoPilot's own TUN (or another VPN) during
        // reload. Doing so creates a routing loop and makes libbox report no usable interface.
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (Build.VERSION.SDK_INT == 23) {  // workarounds for OEM bugs
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        val generation = ++registrationGeneration
        val callback = createCallback(generation)
        activeRegistrationGeneration = generation
        registeredCallback = callback
        try {
            fallback = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> @TargetApi(31) {
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(
                        request, callback, mainHandler
                    )
                }
                in 28 until 31 -> @TargetApi(28) {  // we want REQUEST here instead of LISTEN
                    SagerNet.connectivity.requestNetwork(request, callback, mainHandler)
                }
                in 26 until 28 -> @TargetApi(26) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(callback, mainHandler)
                }
                in 24 until 26 -> @TargetApi(24) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(callback)
                }
                else -> {
                    SagerNet.connectivity.requestNetwork(request, callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
            callbackRegistered = true
        } catch (e: Exception) {
            runCatching { Logs.w(e) }
            fallback = true
            callbackRegistered = false
            activeRegistrationGeneration = 0L
            registeredCallback = null
        }
    }

    private fun unregister() {
        val callback = registeredCallback
        if (!callbackRegistered || callback == null) return
        callbackRegistered = false
        activeRegistrationGeneration = 0L
        registeredCallback = null
        runCatching {
            SagerNet.connectivity.unregisterNetworkCallback(callback)
        }.onFailure { error ->
            runCatching {
                Logs.w("Default network callback cleanup failed (${error.javaClass.simpleName})")
            }
        }
    }
}
