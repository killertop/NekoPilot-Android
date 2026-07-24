package io.nekohasekai.sagernet.bg

/**
 * Tracks a candidate resource separately while its native owner starts or reloads.
 *
 * The active resource deliberately lives outside this class. For a TUN, Android can make a newly
 * established interface current before the native start call returns, so callers must promote
 * that descriptor for recovery instead of treating [takePending] as a system-routing rollback. All
 * methods are synchronized because native platform callbacks may arrive on a different thread
 * from the reload owner.
 */
internal class StagedResourceSwap<T> {
    private val lock = Any()
    private var inProgress = false
    private var candidate: T? = null

    fun begin() = synchronized(lock) {
        check(!inProgress) { "A staged resource swap is already in progress" }
        check(candidate == null) { "A stale candidate resource is still present" }
        inProgress = true
    }

    fun isInProgress(): Boolean = synchronized(lock) { inProgress }

    fun stage(value: T) = synchronized(lock) {
        check(inProgress) { "No staged resource swap is in progress" }
        check(candidate == null) { "Only one candidate resource may be staged" }
        candidate = value
    }

    fun commit(): T? = synchronized(lock) {
        check(inProgress) { "No staged resource swap is in progress" }
        candidate.also {
            candidate = null
            inProgress = false
        }
    }

    /** Removes the pending resource for terminal teardown; this is not an Android route rollback. */
    fun takePending(): T? = synchronized(lock) {
        candidate.also {
            candidate = null
            inProgress = false
        }
    }
}
