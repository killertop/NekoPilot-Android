package io.nekohasekai.sagernet.utils

internal const val FIRST_APPLICATION_UID = 10_000

internal fun isPerAppSelectableUid(uid: Int): Boolean = uid >= FIRST_APPLICATION_UID

internal fun sanitizePerAppPackages(
    selectedPackages: Iterable<String>,
    installedUids: Map<String, Int>,
): LinkedHashSet<String> = selectedPackages
    .map { it.trim().removePrefix("\uFEFF") }
    .filter { it.isNotEmpty() }
    .filterTo(linkedSetOf()) { packageName ->
        installedUids[packageName]?.let(::isPerAppSelectableUid) != false
    }
