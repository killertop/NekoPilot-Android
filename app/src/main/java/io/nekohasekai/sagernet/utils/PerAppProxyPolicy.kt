package io.nekohasekai.sagernet.utils

internal const val FIRST_APPLICATION_UID = 10_000
private const val PER_USER_UID_RANGE = 100_000

internal fun isPerAppSelectableUid(uid: Int): Boolean =
    uid >= 0 && uid % PER_USER_UID_RANGE >= FIRST_APPLICATION_UID

/**
 * The persisted per-app VPN policy, normalized independently of the UI. Package order has no
 * routing meaning, so a canonical order prevents a package-cache refresh from looking like a
 * user edit.
 */
internal class PerAppProxyPolicy private constructor(
    val enabled: Boolean,
    val packages: Set<String>,
) {
    val serializedPackages: String
        get() = packages.joinToString("\n")

    companion object {
        fun create(enabled: Boolean, packages: Iterable<String>): PerAppProxyPolicy =
            PerAppProxyPolicy(enabled, normalizePerAppPackages(packages).toSortedSet())

        fun fromStorage(enabled: Boolean, serializedPackages: String): PerAppProxyPolicy =
            create(enabled, serializedPackages.lineSequence().asIterable())
    }

    override fun equals(other: Any?): Boolean =
        other is PerAppProxyPolicy && enabled == other.enabled && packages == other.packages

    override fun hashCode(): Int = 31 * enabled.hashCode() + packages.hashCode()

    override fun toString(): String =
        "PerAppProxyPolicy(enabled=$enabled, packages=$packages)"
}

/**
 * Holds a local editing transaction for the Android allow-list. It intentionally knows nothing
 * about DataStore or service lifecycle: callers persist [policy] only after an explicit Apply.
 */
internal class PerAppProxyPolicyDraft(initial: PerAppProxyPolicy) {
    private var baseline = initial
    private var current = initial

    val policy: PerAppProxyPolicy
        get() = current
    val committedPolicy: PerAppProxyPolicy
        get() = baseline
    val enabled: Boolean
        get() = current.enabled
    val packages: Set<String>
        get() = current.packages
    val isDirty: Boolean
        get() = current != baseline
    val changeCount: Int
        get() = (if (baseline.enabled != current.enabled) 1 else 0) +
            (baseline.packages union current.packages).count { packageName ->
                (packageName in baseline.packages) != (packageName in current.packages)
            }

    fun setEnabled(enabled: Boolean) {
        current = PerAppProxyPolicy.create(enabled, current.packages)
    }

    fun replacePackages(packages: Iterable<String>) {
        current = PerAppProxyPolicy.create(current.enabled, packages)
    }

    /** Restores a configuration-change snapshot while retaining the original persisted baseline. */
    fun restoreDraft(policy: PerAppProxyPolicy) {
        current = policy
    }

    /** Marks exactly the durably persisted policy as committed. */
    fun markCommitted(policy: PerAppProxyPolicy = current) {
        baseline = policy
    }

    fun discard(): PerAppProxyPolicy {
        current = baseline
        return current
    }
}

internal fun shouldPreparePerAppRecommendations(
    firstEntrySetupPending: Boolean,
    draftIsEmpty: Boolean,
    restoredPending: Boolean?,
): Boolean = firstEntrySetupPending && draftIsEmpty && (restoredPending != false)

internal fun normalizePerAppPackages(selectedPackages: Iterable<String>): LinkedHashSet<String> =
    selectedPackages
        .map { it.trim().removePrefix("\uFEFF") }
        .filterTo(linkedSetOf(), String::isNotEmpty)

internal fun sanitizePerAppPackages(
    selectedPackages: Iterable<String>,
    installedUids: Map<String, Int>,
): LinkedHashSet<String> = normalizePerAppPackages(selectedPackages)
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
    normalizePerAppPackages(savedPackages)
        .filter { it !in visiblePackages }
        .forEach(::add)
    normalizePerAppPackages(selectedVisiblePackages)
        .forEach(::add)
}
