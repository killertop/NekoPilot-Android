package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DefaultNetworkFallbackPolicyTest {
    @Test
    fun fallbackStatePublishesOnlyRealNetworkTransitions() {
        val state = FallbackNetworkState<String>()

        assertFalse(state.update(null))
        assertTrue(state.update("wifi"))
        assertFalse(state.update("wifi"))
        assertTrue(state.update("cellular"))
        assertTrue(state.update(null))
        assertFalse(state.update(null))
    }

    @Test
    fun registrationRetryUsesBoundedExponentialBackoff() {
        assertEquals(1_000L, registrationRetryDelayMillis(0))
        assertEquals(2_000L, registrationRetryDelayMillis(1))
        assertEquals(4_000L, registrationRetryDelayMillis(2))
        assertEquals(16_000L, registrationRetryDelayMillis(4))
        assertEquals(30_000L, registrationRetryDelayMillis(5))
        assertEquals(30_000L, registrationRetryDelayMillis(30))
    }

    @Test
    fun uncertainRegistrationFailureAlwaysAttemptsCompensatingUnregister() {
        val callback = Any()
        var accepted = false
        var compensated: Any? = null

        val result = registerWithCompensation(
            callback = callback,
            register = {
                accepted = true
                throw IllegalStateException("Binder reply failed")
            },
            unregister = {
                if (accepted) compensated = it
            },
        )

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(compensated === callback)
    }

    @Test
    fun successfulRegistrationDoesNotUnregister() {
        var unregisterCount = 0

        val result = registerWithCompensation(
            callback = Any(),
            register = {},
            unregister = { unregisterCount++ },
        )

        assertTrue(result.isSuccess)
        assertEquals(0, unregisterCount)
    }

    @Test
    fun alreadyUnregisteredCallbackIsSuccessfulCleanup() {
        unregisterTreatingMissingAsSuccess(Any()) {
            throw IllegalArgumentException("Network callback was not registered")
        }
    }

    @Test
    fun callbackCannotSynchronouslyAwaitItsOwnActorFence() {
        var rejected = false
        val token = NetworkListenerToken<Unit>(Any()) {
            rejected = runCatching { checkNotInNetworkListenerCallback() }.isFailure
        }

        assertTrue(token.dispatch(Unit))
        assertTrue(rejected)
        checkNotInNetworkListenerCallback()
    }

    @Test
    fun oldLeaseCloseCannotRemoveLaterRegistrationWithSameOwnerKey() {
        val registry = NetworkListenerRegistrarState<String>()
        val owner = Any()
        val oldLease = 1L
        val newLease = 2L
        val oldToken = NetworkListenerToken<String>(owner) {}
        val newToken = NetworkListenerToken<String>(owner) {}
        assertTrue(registry.register(oldLease, oldToken))
        assertTrue(registry.register(newLease, newToken))

        oldToken.deactivate()
        assertTrue(registry.release(oldLease).removed)
        assertEquals(listOf(newToken), registry.listeners())
        assertEquals(1, registry.size())
        assertTrue(registry.release(newLease).becameEmpty)
    }

    @Test
    fun cancellationAfterSendBeforeResponseClosesAndJoinsLease() = runBlocking {
        val sent = CompletableDeferred<Unit>()
        val allowResponse = CompletableDeferred<Unit>()
        val releaseCompleted = CompletableDeferred<Unit>()
        val closeCount = AtomicInteger()
        val registerCount = AtomicInteger()
        val unregisterCount = AtomicInteger()
        val registrar = NetworkListenerRegistrarState<Unit>(
            registerSystemCallback = { registerCount.incrementAndGet() },
            unregisterSystemCallback = { unregisterCount.incrementAndGet() },
        )
        val token = NetworkListenerToken<Unit>(Any()) {}

        val start = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitCancellableLeaseStart(
                lease = token,
                send = {
                    assertTrue(registrar.register(1L, token))
                    sent.complete(Unit)
                },
                awaitStarted = { allowResponse.await() },
                closeAndJoin = {
                    it.deactivate()
                    registrar.release(1L)
                    closeCount.incrementAndGet()
                    releaseCompleted.complete(Unit)
                    releaseCompleted.await()
                },
            )
        }
        sent.await()
        start.cancelAndJoin()

        assertEquals(1, closeCount.get())
        assertEquals(1, registerCount.get())
        assertEquals(1, unregisterCount.get())
        assertTrue(registrar.isEmpty())
        assertTrue(releaseCompleted.isCompleted)
    }

    @Test
    fun closeBlocksAPreviouslyCopiedNetworkEvent() {
        val calls = AtomicInteger()
        val token = NetworkListenerToken<Any?>(Any()) { calls.incrementAndGet() }
        val registry = NetworkListenerRegistrarState<Any?>()
        assertTrue(registry.register(1L, token))
        val copiedListeners = registry.listeners()

        token.deactivate() // Lease.close() linearization point.
        copiedListeners.forEach { it.dispatch(Any()) }

        assertEquals(0, calls.get())
    }

    @Test
    fun cancellationDuringSystemRegistrationRollsBackSameRegistrar() {
        val unregisterCount = AtomicInteger()
        lateinit var token: NetworkListenerToken<Unit>
        val registrar = NetworkListenerRegistrarState<Unit>(
            registerSystemCallback = { token.deactivate() },
            unregisterSystemCallback = { unregisterCount.incrementAndGet() },
        )
        token = NetworkListenerToken(Any()) {}

        assertFalse(registrar.register(1L, token))
        assertTrue(registrar.isEmpty())
        assertEquals(1, unregisterCount.get())
    }

    @Test
    fun onlyFinalLeaseRequestsCallbackUnregistration() {
        val registerCount = AtomicInteger()
        val unregisterCount = AtomicInteger()
        val registry = NetworkListenerRegistrarState<Unit>(
            registerSystemCallback = { registerCount.incrementAndGet() },
            unregisterSystemCallback = { unregisterCount.incrementAndGet() },
        )
        assertTrue(registry.register(1L, NetworkListenerToken(Any()) {}))
        assertTrue(registry.register(2L, NetworkListenerToken(Any()) {}))
        assertEquals(1, registerCount.get())

        registry.release(1L)
        assertEquals(0, unregisterCount.get())
        registry.release(2L)
        assertEquals(1, unregisterCount.get())
    }

    @Test
    fun finalUnregisterFailureRetainsLastReleaseOutcome() {
        val registrar = NetworkListenerRegistrarState<Unit>(
            unregisterSystemCallback = { throw IllegalStateException("binder failure") },
        )
        assertTrue(registrar.register(1L, NetworkListenerToken(Any()) {}))

        val error = runCatching { registrar.release(1L) }.exceptionOrNull()

        assertTrue(error is RegistrarUnregisterException)
        assertTrue((error as RegistrarUnregisterException).releaseResult.becameEmpty)
        assertTrue(registrar.isEmpty())
    }

    @Test
    fun staleCallbackAndDelayedCloseCannotAffectNewGeneration() {
        val registry = NetworkListenerRegistrarState<Unit>()
        val owner = Any()
        val oldLease = 1L
        val newLease = 2L
        assertTrue(registry.register(oldLease, NetworkListenerToken(owner) {}))
        assertTrue(registry.register(newLease, NetworkListenerToken(owner) {}))

        assertFalse(
            isActiveNetworkCallbackGeneration(
                listenerCount = registry.size(),
                callbackRegistered = true,
                eventGeneration = 41L,
                activeRegistrationGeneration = 42L,
            ),
        )
        registry.release(oldLease) // delayed cleanup from the old lifecycle
        assertTrue(
            isActiveNetworkCallbackGeneration(
                listenerCount = registry.size(),
                callbackRegistered = true,
                eventGeneration = 42L,
                activeRegistrationGeneration = 42L,
            ),
        )
        assertEquals(1, registry.size())
        registry.release(newLease)
    }

    @Test
    fun closeAndNewStartHaveDeterministicIndependentOrdering() {
        val registerCount = AtomicInteger()
        val unregisterCount = AtomicInteger()
        val registry = NetworkListenerRegistrarState<String>(
            registerSystemCallback = { registerCount.incrementAndGet() },
            unregisterSystemCallback = { unregisterCount.incrementAndGet() },
        )
        val owner = Any()
        val old = NetworkListenerToken<String>(owner) {}
        val new = NetworkListenerToken<String>(owner) {}
        assertTrue(registry.register(1L, old))

        // Start-new then release-old must retain the system registration.
        assertTrue(registry.register(2L, new))
        old.deactivate()
        assertFalse(registry.release(1L).becameEmpty)
        assertEquals(listOf(new), registry.listeners())
        assertTrue(registry.release(2L).becameEmpty)
        assertEquals(1, registerCount.get())
        assertEquals(1, unregisterCount.get())

        // Release-old then start-new crosses a clean unregister/register boundary.
        val secondRegisterCount = AtomicInteger()
        val secondUnregisterCount = AtomicInteger()
        val secondState = NetworkListenerRegistrarState<String>(
            registerSystemCallback = { secondRegisterCount.incrementAndGet() },
            unregisterSystemCallback = { secondUnregisterCount.incrementAndGet() },
        )
        assertTrue(secondState.register(3L, NetworkListenerToken(owner) {}))
        assertTrue(secondState.release(3L).becameEmpty)
        assertTrue(secondState.register(4L, NetworkListenerToken(owner) {}))
        assertEquals(2, secondRegisterCount.get())
        assertEquals(1, secondUnregisterCount.get())
    }

    @Test
    fun repeatedCloseAndCloseAndJoinShareOneReleaseFence() = runBlocking {
        val releaseCount = AtomicInteger()
        val fence = CompletableDeferred<Unit>()
        val closer = IdempotentLeaseCloser(
            deactivate = {},
            enqueueRelease = {
                releaseCount.incrementAndGet()
                fence.invokeOnCompletion { error ->
                    if (error == null) it.complete(Unit) else it.completeExceptionally(error)
                }
            },
        )

        closer.close()
        closer.close()
        val join = launch(start = CoroutineStart.UNDISPATCHED) { closer.closeAndJoin() }
        assertEquals(1, releaseCount.get())
        assertFalse(join.isCompleted)
        fence.complete(Unit)
        join.join()
        closer.closeAndJoin()
        assertEquals(1, releaseCount.get())
    }
}
