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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.withContext
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val networkListenerCallbackDepth = ThreadLocal<Int>()

internal fun checkNotInNetworkListenerCallback() {
    check((networkListenerCallbackDepth.get() ?: 0) == 0) {
        "closeAndJoin cannot be awaited from a default network listener callback"
    }
}

internal fun <T> unregisterTreatingMissingAsSuccess(
    callback: T,
    unregister: (T) -> Unit,
) {
    try {
        unregister(callback)
    } catch (_: IllegalArgumentException) {
        // ConnectivityManager uses IllegalArgumentException for an already-unregistered callback.
    }
}

internal class FallbackNetworkState<T> {
    var current: T? = null
        private set

    fun update(value: T?): Boolean {
        if (current == value) return false
        current = value
        return true
    }

    fun clear() {
        current = null
    }
}

internal fun registrationRetryDelayMillis(attempt: Int): Long {
    require(attempt >= 0) { "Retry attempt must not be negative" }
    var delay = INITIAL_REGISTRATION_RETRY_DELAY_MS
    repeat(attempt.coerceAtMost(30)) {
        delay = (delay * 2L).coerceAtMost(MAX_REGISTRATION_RETRY_DELAY_MS)
    }
    return delay
}

internal fun <T> registerWithCompensation(
    callback: T,
    register: (T) -> Unit,
    unregister: (T) -> Unit,
): Result<Unit> = runCatching {
    register(callback)
}.onFailure { registrationError ->
    // ConnectivityService can accept a callback and still fail while returning across Binder.
    // Always attempt unregister with the exact callback; "not registered" failures are harmless.
    runCatching { unregister(callback) }.exceptionOrNull()?.let(registrationError::addSuppressed)
}

internal class NetworkListenerToken<T>(
    val key: Any,
    private val listener: (T) -> Unit,
) {
    private val lock = Any()
    private var active = true

    fun isActive(): Boolean = synchronized(lock) { active }

    fun deactivate(): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        active = false
        true
    }

    /**
     * Linearizes callback entry with close(): once deactivate() returns, no later dispatch can
     * enter listener code, including a dispatch copied out of the actor before the close.
     */
    fun dispatch(value: T): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        val previousDepth = networkListenerCallbackDepth.get() ?: 0
        networkListenerCallbackDepth.set(previousDepth + 1)
        try {
            listener(value)
            true
        } finally {
            if (previousDepth == 0) networkListenerCallbackDepth.remove()
            else networkListenerCallbackDepth.set(previousDepth)
        }
    }
}

internal class NetworkListenerRegistrarState<T>(
    private val registerSystemCallback: () -> Unit = {},
    private val unregisterSystemCallback: () -> Unit = {},
) {
    data class ReleaseResult(val removed: Boolean, val becameEmpty: Boolean)

    private val registrations = LinkedHashMap<Long, NetworkListenerToken<T>>()

    @Synchronized
    fun register(leaseId: Long, token: NetworkListenerToken<T>): Boolean {
        check(!registrations.containsKey(leaseId)) { "Duplicate default network lease id" }
        if (!token.isActive()) return false
        val wasEmpty = registrations.isEmpty()
        if (wasEmpty) registerSystemCallback()
        if (!token.isActive()) {
            if (wasEmpty) unregisterSystemCallback()
            return false
        }
        registrations[leaseId] = token
        return true
    }

    @Synchronized
    fun release(leaseId: Long): ReleaseResult {
        val removed = registrations.remove(leaseId) != null
        val becameEmpty = removed && registrations.isEmpty()
        val unregisterError = if (becameEmpty) {
            runCatching(unregisterSystemCallback).exceptionOrNull()
        } else {
            null
        }
        return ReleaseResult(removed, becameEmpty).also {
            unregisterError?.let { error -> throw RegistrarUnregisterException(it, error) }
        }
    }

    @Synchronized
    fun listeners(): List<NetworkListenerToken<T>> = registrations.values.toList()

    @Synchronized
    fun isEmpty(): Boolean = registrations.isEmpty()

    @Synchronized
    fun size(): Int = registrations.size
}

internal class RegistrarUnregisterException(
    val releaseResult: NetworkListenerRegistrarState.ReleaseResult,
    cause: Throwable,
) : Exception(cause)

internal fun isActiveNetworkCallbackGeneration(
    listenerCount: Int,
    callbackRegistered: Boolean,
    eventGeneration: Long,
    activeRegistrationGeneration: Long,
): Boolean = listenerCount > 0 && callbackRegistered &&
    eventGeneration == activeRegistrationGeneration

internal suspend fun <T> awaitCancellableLeaseStart(
    lease: T,
    send: suspend () -> Unit,
    awaitStarted: suspend () -> Unit,
    closeAndJoin: suspend (T) -> Unit,
): T {
    try {
        send()
        awaitStarted()
        return lease
    } catch (error: CancellationException) {
        withContext(NonCancellable) { closeAndJoin(lease) }
        throw error
    } catch (error: Throwable) {
        withContext(NonCancellable) { runCatching { closeAndJoin(lease) } }
        throw error
    }
}

internal class IdempotentLeaseCloser(
    private val deactivate: () -> Unit,
    private val enqueueRelease: (CompletableDeferred<Unit>) -> Unit,
) {
    private val closeRequested = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()

    fun close() {
        deactivate()
        if (closeRequested.compareAndSet(false, true)) {
            try {
                enqueueRelease(closed)
            } catch (error: Throwable) {
                closed.completeExceptionally(error)
            }
        }
    }

    suspend fun closeAndJoin() {
        close()
        closed.await()
    }
}

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val lease: Lease) : NetworkMessage()
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Release(val leaseId: Long) : NetworkMessage() {
            val completed = CompletableDeferred<Unit>()
        }

        class Retry(val generation: Long) : NetworkMessage()
        class Put(val generation: Long, val network: Network) : NetworkMessage()
        class Update(val generation: Long, val network: Network) : NetworkMessage()
        class Lost(val generation: Long, val network: Network) : NetworkMessage()
    }

    /** A unique registration owner; only this lease can release its listener. */
    class Lease internal constructor(
        internal val leaseId: Long,
        internal val token: NetworkListenerToken<Network?>,
    ) : AutoCloseable {
        internal val started = CompletableDeferred<Unit>()
        private val closer = IdempotentLeaseCloser(
            deactivate = token::deactivate,
            enqueueRelease = { closed ->
                val message = NetworkMessage.Release(leaseId)
                val result = networkActor.trySend(message)
                if (result.isFailure) {
                    closed.completeExceptionally(
                        result.exceptionOrNull()
                            ?: IllegalStateException("Default network listener actor is closed"),
                    )
                } else {
                    message.completed.invokeOnCompletion { error ->
                        if (error == null) closed.complete(Unit)
                        else closed.completeExceptionally(error)
                    }
                }
            },
        )

        override fun close() {
            closer.close()
        }

        /**
         * Waits for the actor/system-unregister fence. Callers must not blockingly bridge this
         * method from inside the listener callback, which itself is serialized by the actor.
         */
        suspend fun closeAndJoin() {
            checkNotInNetworkListenerCallback()
            closer.closeAndJoin()
        }
    }

    private val nextLeaseId = AtomicLong()

    private fun allocateLeaseId(): Long {
        while (true) {
            val current = nextLeaseId.get()
            check(current != Long.MAX_VALUE) { "Default network listener lease ids exhausted" }
            val next = current + 1L
            if (nextLeaseId.compareAndSet(current, next)) return next
        }
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private val networkActor = applicationScope.actor<NetworkMessage>(
        // Modern registrations explicitly deliver callbacks on mainHandler. Never run listeners
        // inline from trySend: a VPN listener can enter JNI and query network interfaces.
        context = Dispatchers.Default,
        capacity = Channel.UNLIMITED,
    ) {
        val listeners = NetworkListenerRegistrarState<Network?>(
            registerSystemCallback = ::register,
            unregisterSystemCallback = ::unregister,
        )
        var network: Network? = null
        var observedNetwork: Network? = null
        val fallbackState = FallbackNetworkState<Network>()
        val pendingRequests = arrayListOf<NetworkMessage.Get>()
        fun notifyListener(listener: NetworkListenerToken<Network?>, value: Network?) {
            try {
                listener.dispatch(value)
            } catch (error: Throwable) {
                runCatching {
                    Logs.w("Default network listener failed (${error.javaClass.simpleName})")
                }
            }
        }
        fun notifyListeners(value: Network?) {
            listeners.listeners().forEach { notifyListener(it, value) }
        }
        fun refreshFallbackNetwork(notifyChanges: Boolean): Network? {
            val current = SagerNet.connectivity.findPhysicalInternetNetwork()
            val changed = fallbackState.update(current)
            network = current
            observedNetwork = current
            if (current != null) {
                pendingRequests.forEach { it.response.complete(current) }
                pendingRequests.clear()
            }
            if (notifyChanges && changed) notifyListeners(current)
            return current
        }
        fun clearNetworkState() {
            network = null
            observedNetwork = null
            fallbackState.clear()
            pendingRequests.forEach {
                it.response.completeExceptionally(UnknownHostException())
            }
            pendingRequests.clear()
        }
        try {
        for (message in channel) try {
            when (message) {
            is NetworkMessage.Start -> {
                val lease = message.lease
                try {
                    if (!lease.token.isActive()) {
                        lease.started.complete(Unit)
                        continue
                    }
                    if (!listeners.register(lease.leaseId, lease.token)) {
                        lease.started.complete(Unit)
                        continue
                    }
                    val current = if (fallback) {
                        refreshFallbackNetwork(notifyChanges = false)
                    } else {
                        network
                    }
                    notifyListener(lease.token, current)
                    lease.started.complete(Unit)
                } catch (error: Throwable) {
                    lease.token.deactivate()
                    val cleanupError = runCatching {
                        listeners.release(lease.leaseId)
                    }.exceptionOrNull()
                    lease.started.completeExceptionally(cleanupError ?: error)
                }
            }
            is NetworkMessage.Get -> {
                if (listeners.isEmpty()) {
                    message.response.completeExceptionally(UnknownHostException())
                } else if (fallback) {
                    val active = refreshFallbackNetwork(notifyChanges = true)
                    if (active == null) {
                        message.response.completeExceptionally(UnknownHostException())
                    } else {
                        message.response.complete(active)
                    }
                } else {
                    val current = network
                    if (current == null) {
                        pendingRequests += message
                    } else {
                        message.response.complete(current)
                    }
                }
            }
            is NetworkMessage.Release -> {
                try {
                    if (listeners.release(message.leaseId).becameEmpty) {
                        clearNetworkState()
                    }
                    message.completed.complete(Unit)
                } catch (error: Throwable) {
                    if (
                        error is RegistrarUnregisterException &&
                        error.releaseResult.becameEmpty
                    ) {
                        clearNetworkState()
                    }
                    message.completed.completeExceptionally(error)
                }
            }

            is NetworkMessage.Put -> {
                if (!message.isFromActiveRegistration(listeners)) continue
                observedNetwork = message.network
                val current = message.network.takeIf { isUsableNetwork(it) }
                network = current
                if (current != null) {
                    pendingRequests.forEach { it.response.complete(current) }
                    pendingRequests.clear()
                }
                notifyListeners(current)
            }
            is NetworkMessage.Update -> if (
                message.isFromActiveRegistration(listeners) && observedNetwork == message.network
            ) {
                val current = message.network.takeIf { isUsableNetwork(it) }
                network = current
                if (current != null) {
                    pendingRequests.forEach { it.response.complete(current) }
                    pendingRequests.clear()
                }
                notifyListeners(current)
            }
            is NetworkMessage.Lost -> if (observedNetwork == message.network) {
                if (!message.isFromActiveRegistration(listeners)) continue
                network = null
                observedNetwork = null
                notifyListeners(null)
            }
            is NetworkMessage.Retry -> if (
                !listeners.isEmpty() &&
                fallback &&
                message.generation == activeRetryGeneration
            ) {
                registrationRetryPending = false
                registrationRetryRunnable = null
                refreshFallbackNetwork(notifyChanges = true)
                register()
            }
            }
        } catch (error: Throwable) {
            when (message) {
                is NetworkMessage.Start -> {
                    message.lease.token.deactivate()
                    val cleanupError = runCatching {
                        listeners.release(message.lease.leaseId)
                    }.exceptionOrNull()
                    message.lease.started.completeExceptionally(cleanupError ?: error)
                }
                is NetworkMessage.Get -> message.response.completeExceptionally(error)
                is NetworkMessage.Release -> message.completed.completeExceptionally(error)
                is NetworkMessage.Retry -> {
                    runCatching {
                        Logs.w("Default network retry failed (${error.javaClass.simpleName})")
                    }
                    if (
                        !listeners.isEmpty() &&
                        fallback &&
                        message.generation == activeRetryGeneration
                    ) {
                        registrationRetryPending = false
                        registrationRetryRunnable = null
                        scheduleRegistrationRetry(message.generation)
                    }
                }
                else -> runCatching {
                    Logs.w("Default network actor message failed (${error.javaClass.simpleName})")
                }
            }
        }
        } finally {
            val closed = CancellationException("Default network listener actor terminated")
            listeners.listeners().forEach(NetworkListenerToken<Network?>::deactivate)
            runCatching { unregister() }
            pendingRequests.forEach { it.response.completeExceptionally(closed) }
            pendingRequests.clear()
            while (true) {
                val queued = channel.tryReceive().getOrNull() ?: break
                when (queued) {
                    is NetworkMessage.Start -> {
                        queued.lease.token.deactivate()
                        queued.lease.started.completeExceptionally(closed)
                    }
                    is NetworkMessage.Get -> queued.response.completeExceptionally(closed)
                    is NetworkMessage.Release -> queued.completed.completeExceptionally(closed)
                    else -> Unit
                }
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit): Lease {
        val leaseId = allocateLeaseId()
        val lease = Lease(leaseId, NetworkListenerToken(key, listener))
        // Cancellation can race every stage after send. Synchronous token invalidation blocks
        // callbacks immediately; the non-cancellable actor fence guarantees no orphan lease or
        // system callback remains when start() returns cancellation to its caller.
        return awaitCancellableLeaseStart(
            lease = lease,
            send = { networkActor.send(NetworkMessage.Start(lease)) },
            awaitStarted = { lease.started.await() },
            closeAndJoin = Lease::closeAndJoin,
        )
    }

    suspend fun get() = NetworkMessage.Get().run {
        networkActor.send(this)
        response.await()
    }

    private fun NetworkMessage.isFromActiveRegistration(
        listeners: NetworkListenerRegistrarState<Network?>,
    ): Boolean {
        val generation = when (this) {
            is NetworkMessage.Put -> generation
            is NetworkMessage.Update -> generation
            is NetworkMessage.Lost -> generation
            else -> return false
        }
        return isActiveNetworkCallbackGeneration(
            listenerCount = listeners.size(),
            callbackRegistered = callbackRegistered,
            eventGeneration = generation,
            activeRegistrationGeneration = activeRegistrationGeneration,
        )
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
    private val callbacksPendingUnregister =
        LinkedHashSet<ConnectivityManager.NetworkCallback>()
    private var registrationRetryPending = false
    private var registrationRetryAttempt = 0
    private var activeRetryGeneration = 0L
    private var registrationRetryRunnable: Runnable? = null
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        // The upstream monitor must never select NekoPilot's own TUN (or another VPN) during
        // reload. Doing so creates a routing loop and makes libbox report no usable interface.
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
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
        cleanupPendingSystemCallbacks()
        val generation = ++registrationGeneration
        val callback = createCallback(generation)
        activeRegistrationGeneration = generation
        registeredCallback = callback
        val result = registerWithCompensation(
            callback = callback,
            register = ::registerSystemCallback,
            unregister = ::unregisterSystemCallback,
        )
        result.fold(
            onSuccess = {
                fallback = false
                callbackRegistered = true
                registrationRetryAttempt = 0
            },
            onFailure = { error ->
                runCatching { Logs.w(error) }
                if (error.suppressed.isNotEmpty()) {
                    callbacksPendingUnregister += callback
                }
                fallback = true
                callbackRegistered = false
                activeRegistrationGeneration = 0L
                registeredCallback = null
                scheduleRegistrationRetry(generation)
            },
        )
    }

    private fun registerSystemCallback(callback: ConnectivityManager.NetworkCallback) {
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
    }

    private fun unregisterSystemCallback(callback: ConnectivityManager.NetworkCallback) {
        unregisterTreatingMissingAsSuccess(
            callback = callback,
            unregister = SagerNet.connectivity::unregisterNetworkCallback,
        )
    }

    private fun cleanupPendingSystemCallbacks() {
        var firstFailure: Throwable? = null
        callbacksPendingUnregister.toList().forEach { callback ->
            runCatching {
                unregisterSystemCallback(callback)
            }.onSuccess {
                callbacksPendingUnregister.remove(callback)
            }.onFailure { error ->
                if (firstFailure == null) firstFailure = error
                else firstFailure?.addSuppressed(error)
            }
        }
        firstFailure?.let { throw it }
    }

    private fun scheduleRegistrationRetry(generation: Long) {
        if (registrationRetryPending) return
        registrationRetryPending = true
        activeRetryGeneration = generation
        val delay = registrationRetryDelayMillis(registrationRetryAttempt++)
        val retry = Runnable {
            networkActor.trySend(NetworkMessage.Retry(generation))
        }
        registrationRetryRunnable = retry
        mainHandler.postDelayed(retry, delay)
    }

    private fun unregister() {
        registrationRetryRunnable?.let(mainHandler::removeCallbacks)
        registrationRetryRunnable = null
        registrationRetryPending = false
        registrationRetryAttempt = 0
        activeRetryGeneration = 0L
        val callback = registeredCallback
        callbackRegistered = false
        activeRegistrationGeneration = 0L
        registeredCallback = null
        callback?.let(callbacksPendingUnregister::add)
        // Propagate a real unregister failure to closeAndJoin(). The active token and callback
        // generation are already invalidated, but callers that own a teardown fence must not be
        // told that system cleanup succeeded when ConnectivityService rejected it.
        cleanupPendingSystemCallbacks()
    }

    private fun isUsableNetwork(network: Network): Boolean =
        SagerNet.connectivity.isPhysicalInternetNetwork(network)
}

private const val INITIAL_REGISTRATION_RETRY_DELAY_MS = 1_000L
private const val MAX_REGISTRATION_RETRY_DELAY_MS = 30_000L
