package io.nekohasekai.sagernet.utils

/**
 * A small generation/CAS gate for resources returned by asynchronous starts.
 *
 * Callbacks and publication use the same token. invalidate() synchronously makes the token stale
 * and detaches the installed resource, so a late result can only be rejected and closed.
 */
internal class LeasePublicationGate<T : AutoCloseable> {
    class Token internal constructor(val generation: Long)
    data class OpenResult<T>(val token: Token, val displaced: T?)
    data class PublishResult<T>(val accepted: Boolean, val displaced: T?)

    private var generation = 0L
    private var accepting = false
    private var installed: Pair<Token, T>? = null

    @Synchronized
    fun open(): OpenResult<T> {
        check(generation != Long.MAX_VALUE) { "Lease publication generations exhausted" }
        val token = Token(++generation)
        accepting = true
        val displaced = installed?.second
        installed = null
        return OpenResult(token, displaced)
    }

    @Synchronized
    fun isCurrent(token: Token): Boolean =
        accepting && token.generation == generation

    /**
     * Publishes only into the generation that initiated the asynchronous start.
     *
     * The caller closes [value] when rejected and closes [PublishResult.displaced] after leaving
     * any lifecycle lock. Resource close can synchronously wait for an in-flight callback.
     */
    @Synchronized
    fun publish(token: Token, value: T): PublishResult<T> {
        if (!accepting || token.generation != generation) {
            return PublishResult(accepted = false, displaced = null)
        }
        val displaced = installed?.second
        installed = token to value
        return PublishResult(accepted = true, displaced = displaced)
    }

    /**
     * Invalidates only [token]. A delayed teardown from an older generation cannot detach a newer
     * lease. The returned resource is already unpublished and is safe to close outside the lock.
     */
    @Synchronized
    fun invalidate(token: Token): T? {
        if (token.generation != generation) return null
        accepting = false
        return installed
            ?.takeIf { it.first == token }
            ?.second
            .also { installed = null }
    }

    /** Invalidates whichever generation is current, for terminal host teardown. */
    @Synchronized
    fun invalidateCurrent(): T? {
        accepting = false
        return installed?.second.also { installed = null }
    }
}
