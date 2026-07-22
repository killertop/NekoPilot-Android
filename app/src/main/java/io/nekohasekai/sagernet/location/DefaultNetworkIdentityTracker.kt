package io.nekohasekai.sagernet.location

/**
 * Distinguishes a real default-network transition from capability updates for the same network.
 *
 * A lost network is remembered as null so that its later return is treated as a new opportunity
 * to retry. The initial available network is also an opportunity because failures may have been
 * recorded before the listener finished registering.
 */
internal class DefaultNetworkIdentityTracker<T> {

    private var current: T? = null

    @Synchronized
    fun shouldForceRetry(network: T?): Boolean {
        val changed = current != network
        current = network
        return network != null && changed
    }
}
