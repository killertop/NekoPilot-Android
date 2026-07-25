package io.nekohasekai.sagernet.bg

/**
 * Owns descriptors handed to native code only for the synchronous duration of a native call.
 *
 * libbox duplicates the callback FD before its start/reload method returns. Keeping the callback
 * descriptor beyond that boundary leaks one Java-owned FD per reload; closing it before return can
 * instead race the native duplication. This lease makes the ownership handoff explicit.
 */
internal class NativeCallbackDescriptorLease<T : AutoCloseable>(
    private val onCloseFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private val descriptors = ArrayList<T>()
    private var active = false

    fun <R> duringNativeCall(block: () -> R): R {
        synchronized(lock) {
            check(!active) { "A native callback descriptor lease is already active" }
            check(descriptors.isEmpty()) { "A native callback descriptor lease was not drained" }
            active = true
        }
        return try {
            block()
        } finally {
            val pending = synchronized(lock) {
                active = false
                val snapshot = descriptors.toList()
                descriptors.clear()
                snapshot
            }
            pending.forEach { descriptor ->
                runCatching { descriptor.close() }.onFailure(onCloseFailure)
            }
        }
    }

    /** Called from the synchronous libbox platform callback before returning its raw FD. */
    fun track(descriptor: T) = synchronized(lock) {
        check(active) { "Native callback descriptor was published outside a native start/reload call" }
        descriptors += descriptor
    }
}
