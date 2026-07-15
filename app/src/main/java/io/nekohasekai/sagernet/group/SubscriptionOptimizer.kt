package io.nekohasekai.sagernet.group

internal fun makeUniqueNames(names: List<String>): List<String> {
    val used = HashSet<String>(names.size)
    val nextSuffix = HashMap<String, Int>()
    return names.map { baseName ->
        var suffix = nextSuffix[baseName] ?: 0
        var name = baseName
        while (!used.add(name)) {
            suffix++
            name = "$baseName ($suffix)"
        }
        nextSuffix[baseName] = suffix
        name
    }
}

internal data class IndexedDeduplication<T>(
    val unique: List<T>,
    val duplicateLabels: List<String>,
)

internal inline fun <T, K> deduplicateIndexed(
    items: List<T>,
    keyOf: (T) -> K,
    nameOf: (T) -> String,
): IndexedDeduplication<T> {
    val unique = LinkedHashMap<K, Pair<Int, T>>(items.size)
    val firstNames = HashMap<K, String>(items.size)
    val duplicateLabels = ArrayList<String>()
    for (item in items) {
        val key = keyOf(item)
        val existing = unique[key]
        if (existing == null) {
            unique[key] = unique.size to item
            firstNames[key] = nameOf(item)
            continue
        }
        val index = existing.first
        firstNames[key]?.let { firstName ->
            val name = firstName.replace(" ($index)", "")
            if (name.isNotBlank()) {
                duplicateLabels.add("$name ($index)")
                firstNames[key] = ""
            }
        }
        duplicateLabels.add(nameOf(item) + " ($index)")
    }
    return IndexedDeduplication(unique.values.map { it.second }, duplicateLabels)
}
