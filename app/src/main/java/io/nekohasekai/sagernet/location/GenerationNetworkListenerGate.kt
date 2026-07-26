package io.nekohasekai.sagernet.location

/**
 * Serializes enablement, generation, asynchronous listener publication, and network identity.
 */
internal class GenerationNetworkListenerGate<L : AutoCloseable, N> {
    class Token internal constructor(val generation: Long)

    private var enabled = false
    private var generation = Long.MIN_VALUE
    private var pending: Token? = null
    private var installed: Pair<Token, L>? = null
    private var currentNetwork: N? = null
    private var retryRequestedGeneration: Long? = null

    /**
     * Moves to an exact repository generation. Any old lease is atomically detached before a late
     * start from that generation can publish.
     */
    @Synchronized
    fun configure(generation: Long, enabled: Boolean): L? {
        if (this.generation == generation && this.enabled == enabled) return null
        this.generation = generation
        this.enabled = enabled
        pending = null
        currentNetwork = null
        retryRequestedGeneration = null
        return installed?.second.also { installed = null }
    }

    @Synchronized
    fun reserveStart(generation: Long): Token? {
        if (!enabled || this.generation != generation) return null
        if (installed?.first?.generation == generation || pending?.generation == generation) {
            return null
        }
        return Token(generation).also { pending = it }
    }

    @Synchronized
    fun publish(token: Token, lease: L): Boolean {
        if (!enabled || token.generation != generation || pending != token) return false
        pending = null
        installed = token to lease
        return true
    }

    @Synchronized
    fun fail(token: Token) {
        if (pending == token) pending = null
    }

    /**
     * Generation validation happens before identity mutation, so an old callback cannot suppress
     * the first retry signal in a newly enabled generation.
     */
    @Synchronized
    fun shouldForceRetry(token: Token, network: N?): Boolean {
        val activeToken = pending ?: installed?.first
        if (!enabled || token.generation != generation || token !== activeToken) return false
        val changed = currentNetwork != network
        currentNetwork = network
        return network != null && changed
    }

    @Synchronized
    fun requestRetry(generation: Long): Boolean {
        if (!enabled || this.generation != generation) return false
        retryRequestedGeneration = generation
        return true
    }

    @Synchronized
    fun consumeRetry(generation: Long): Boolean {
        if (!enabled || retryRequestedGeneration != generation) return false
        retryRequestedGeneration = null
        return true
    }
}
