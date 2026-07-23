package io.nekohasekai.sagernet.bg

/**
 * Holds a candidate resource separately until its owner commits or rejects a reload.
 *
 * The active resource deliberately lives outside this class: a failed candidate can therefore
 * never overwrite or close it. All methods are synchronized because native platform callbacks
 * may arrive on a different thread from the reload owner.
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

    fun rollback(): T? = synchronized(lock) {
        candidate.also {
            candidate = null
            inProgress = false
        }
    }
}
