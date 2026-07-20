package io.nekohasekai.sagernet.ui

/** Keeps successful node tests first and orders them by measured latency. */
internal object NodeLatencyOrder {

    fun <T> sort(
        items: List<T>,
        status: (T) -> Int,
        latencyMs: (T) -> Int,
        stableOrder: (T) -> Long,
    ): List<T> = items.sortedWith(
        compareBy<T> {
            if (status(it) == 1) latencyMs(it) else Int.MAX_VALUE
        }.thenBy(stableOrder)
    )
}
