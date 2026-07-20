package io.nekohasekai.sagernet.ui

/** Keeps successful node tests first and orders them by measured latency. */
internal object NodeLatencyOrder {

    fun <T> comparator(
        status: (T) -> Int,
        latencyMs: (T) -> Int,
        stableOrder: (T) -> Long,
    ): Comparator<T> = compareBy<T> {
        if (status(it) == 1) latencyMs(it) else Int.MAX_VALUE
    }.thenBy(stableOrder)

    fun <T> sort(
        items: List<T>,
        status: (T) -> Int,
        latencyMs: (T) -> Int,
        stableOrder: (T) -> Long,
    ): List<T> = items.sortedWith(comparator(status, latencyMs, stableOrder))
}
