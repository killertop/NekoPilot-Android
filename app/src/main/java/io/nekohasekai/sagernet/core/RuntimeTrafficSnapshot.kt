package io.nekohasekai.sagernet.core

/** Read-only traffic projection shared across the VPN Binder boundary. */
data class RuntimeTrafficSnapshot(
    val available: Boolean,
    val profileId: Long,
    val uplinkBytesPerSecond: Long,
    val downlinkBytesPerSecond: Long,
    val sampledAtElapsedRealtime: Long,
) {
    fun isFresh(nowElapsedRealtime: Long, maxAgeMs: Long = MAX_SAMPLE_AGE_MS): Boolean =
        available && sampledAtElapsedRealtime > 0L &&
            nowElapsedRealtime - sampledAtElapsedRealtime in 0..maxAgeMs

    companion object {
        const val MAX_SAMPLE_AGE_MS = 2_500L

        fun unavailable(nowElapsedRealtime: Long = 0L) = RuntimeTrafficSnapshot(
            available = false,
            profileId = 0L,
            uplinkBytesPerSecond = 0L,
            downlinkBytesPerSecond = 0L,
            sampledAtElapsedRealtime = nowElapsedRealtime,
        )
    }
}
