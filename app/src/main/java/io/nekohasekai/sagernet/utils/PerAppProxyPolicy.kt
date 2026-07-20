package io.nekohasekai.sagernet.utils

internal const val FIRST_APPLICATION_UID = 10_000
private const val PER_USER_UID_RANGE = 100_000

internal fun isPerAppSelectableUid(uid: Int): Boolean =
    uid >= 0 && uid % PER_USER_UID_RANGE >= FIRST_APPLICATION_UID

internal fun sanitizePerAppPackages(
    selectedPackages: Iterable<String>,
    installedUids: Map<String, Int>,
): LinkedHashSet<String> = selectedPackages
    .map { it.trim().removePrefix("\uFEFF") }
    .filter { it.isNotEmpty() }
    .filterTo(linkedSetOf()) { packageName ->
        installedUids[packageName]?.let(::isPerAppSelectableUid) != false
    }

/**
 * Replaces the selection for packages visible in the current PackageManager snapshot while
 * retaining saved packages hidden by OEM permission/visibility restrictions.
 */
internal fun mergeVisiblePerAppSelection(
    savedPackages: Iterable<String>,
    visiblePackages: Set<String>,
    selectedVisiblePackages: Iterable<String>,
): LinkedHashSet<String> = linkedSetOf<String>().apply {
    savedPackages
        .map { it.trim().removePrefix("\uFEFF") }
        .filter { it.isNotEmpty() && it !in visiblePackages }
        .forEach(::add)
    selectedVisiblePackages
        .map { it.trim().removePrefix("\uFEFF") }
        .filter(String::isNotEmpty)
        .forEach(::add)
}
