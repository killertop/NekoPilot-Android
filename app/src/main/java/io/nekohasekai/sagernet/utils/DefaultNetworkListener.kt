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

        object Retry : NetworkMessage()
        class Put(val generation: Long, val network: Network) : NetworkMessage()
        class Update(val generation: Long, val network: Network) : NetworkMessage()
        class Lost(val generation: Long, val network: Network) : NetworkMessage()
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private val networkActor = applicationScope.actor<NetworkMessage>(
        // Modern registrations explicitly deliver callbacks on mainHandler. Never run listeners
        // inline from trySend: a VPN listener can enter JNI and query network interfaces.
        context = Dispatchers.Default,
        capacity = Channel.UNLIMITED,
    ) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null
        var observedNetwork: Network? = null
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
                    SagerNet.connectivity.findPhysicalInternetNetwork()
                } else {
                    network
                }
                notifyListener(message.listener, current)
            }
            is NetworkMessage.Get -> {
                if (listeners.isEmpty()) {
                    message.response.completeExceptionally(UnknownHostException())
                } else if (fallback) {
                    val active = SagerNet.connectivity.findPhysicalInternetNetwork()
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
                    observedNetwork = null
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
                observedNetwork = message.network
                network = message.network.takeIf { isUsableNetwork(it) }
                if (network != null) {
                    pendingRequests.forEach { it.response.complete(network) }
                    pendingRequests.clear()
                }
                notifyListeners(network)
            }
            is NetworkMessage.Update -> if (
                message.isFromActiveRegistration(listeners) && observedNetwork == message.network
            ) {
                network = message.network.takeIf { isUsableNetwork(it) }
                if (network != null) {
                    pendingRequests.forEach { it.response.complete(network) }
                    pendingRequests.clear()
                }
                notifyListeners(network)
            }
            is NetworkMessage.Lost -> if (observedNetwork == message.network) {
                if (!message.isFromActiveRegistration(listeners)) continue
                network = null
                observedNetwork = null
                notifyListeners(null)
            }
            NetworkMessage.Retry -> if (listeners.isNotEmpty() && fallback) {
                registrationRetryPending = false
                register()
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
            // Ignore duplicate capability callbacks, but retain every signal consumed by the VPN:
            // metering, validation (automatic recovery safety), and congestion (libbox platform).
            private var lastReportedCapabilities: UpstreamCapabilities? = null

            override fun onAvailable(network: Network) =
                networkActor.trySend(NetworkMessage.Put(generation, network)).let {
                    lastReportedCapabilities = null
                    Unit
                }

            override fun onCapabilitiesChanged(
                network: Network, networkCapabilities: NetworkCapabilities
            ) {
                val capabilities = networkCapabilities.toUpstreamCapabilities()
                if (lastReportedCapabilities == capabilities) return
                lastReportedCapabilities = capabilities
                networkActor.trySend(NetworkMessage.Update(generation, network)).let { Unit }
            }

            override fun onLost(network: Network) =
                networkActor.trySend(NetworkMessage.Lost(generation, network)).let {
                    lastReportedCapabilities = null
                    Unit
                }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties,
            ) = networkActor.trySend(NetworkMessage.Update(generation, network)).let { Unit }
        }

    private data class UpstreamCapabilities(
        val internet: Boolean,
        val validated: Boolean,
        val metered: Boolean,
        val constrained: Boolean,
    )

    private fun NetworkCapabilities.toUpstreamCapabilities() = UpstreamCapabilities(
        internet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        constrained = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED),
    )

    private var fallback = false
    private var registrationGeneration = 0L
    @Volatile
    private var activeRegistrationGeneration = 0L
    private var callbackRegistered = false
    private var registeredCallback: ConnectivityManager.NetworkCallback? = null
    private var registrationRetryPending = false
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        // The upstream monitor must never select NekoPilot's own TUN (or another VPN) during
        // reload. Doing so creates a routing loop and makes libbox report no usable interface.
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registrationRetry = Runnable {
        networkActor.trySend(NetworkMessage.Retry)
    }

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
                in 26 until 31 -> @TargetApi(26) {
                    SagerNet.connectivity.requestNetwork(request, callback, mainHandler)
                }
                in 24 until 26 -> @TargetApi(24) {
                    SagerNet.connectivity.requestNetwork(request, callback)
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
            scheduleRegistrationRetry()
        }
    }

    private fun scheduleRegistrationRetry() {
        if (registrationRetryPending) return
        registrationRetryPending = true
        mainHandler.postDelayed(registrationRetry, REGISTRATION_RETRY_DELAY_MS)
    }

    private fun unregister() {
        mainHandler.removeCallbacks(registrationRetry)
        registrationRetryPending = false
        val callback = registeredCallback
        if (!callbackRegistered || callback == null) {
            activeRegistrationGeneration = 0L
            registeredCallback = null
            return
        }
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

    private fun isUsableNetwork(network: Network): Boolean =
        SagerNet.connectivity.isPhysicalInternetNetwork(network)

    private const val REGISTRATION_RETRY_DELAY_MS = 5_000L
}
