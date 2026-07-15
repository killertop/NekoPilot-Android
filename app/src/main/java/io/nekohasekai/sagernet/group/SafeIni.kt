package io.nekohasekai.sagernet.group

private const val MAX_INI_CHARS = 1_000_000
private const val MAX_INI_LINES = 20_000
private const val MAX_INI_SECTIONS = 256
private const val MAX_INI_KEY_CHARS = 128
private const val MAX_INI_VALUE_CHARS = 65_536

internal class IniSection(private val entries: Map<String, List<String>>) {
    fun values(key: String): List<String> = entries[key].orEmpty()
    fun value(key: String): String? = entries[key]?.lastOrNull()
}

internal object SafeIni {
    fun parse(text: String): Map<String, List<IniSection>> {
        require(text.length <= MAX_INI_CHARS) { "INI input is too large" }
        val result = linkedMapOf<String, MutableList<IniSection>>()
        var sectionName: String? = null
        var entries = linkedMapOf<String, MutableList<String>>()
        var sectionCount = 0

        fun finishSection() {
            val name = sectionName ?: return
            result.getOrPut(name) { mutableListOf() }
                .add(IniSection(entries.mapValues { it.value.toList() }))
        }

        var lineCount = 0
        text.lineSequence().forEach { rawLine ->
            lineCount++
            require(lineCount <= MAX_INI_LINES) { "INI input has too many lines" }
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach

            if (line.startsWith("[") && line.endsWith("]")) {
                finishSection()
                sectionCount++
                require(sectionCount <= MAX_INI_SECTIONS) { "INI input has too many sections" }
                sectionName = line.substring(1, line.length - 1).trim()
                    .also { require(it.isNotEmpty()) { "INI section name is empty" } }
                entries = linkedMapOf()
                return@forEach
            }

            require(sectionName != null) { "INI property appears before a section" }
            val separator = line.indexOf('=')
            require(separator > 0) { "Invalid INI property" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(key.isNotEmpty() && key.length <= MAX_INI_KEY_CHARS) { "Invalid INI key" }
            require(value.length <= MAX_INI_VALUE_CHARS) { "INI value is too large" }
            entries.getOrPut(key) { mutableListOf() }.add(value)
        }
        finishSection()
        return result.mapValues { it.value.toList() }
    }
}
