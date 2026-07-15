package io.nekohasekai.sagernet.fmt

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

private const val MAX_YAML_CODE_POINTS = 5_000_000
private const val MAX_YAML_ALIASES = 50
private const val MAX_YAML_NESTING_DEPTH = 50

internal object SafeYaml {
    fun loadMap(text: String): Map<String, Any?> {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = MAX_YAML_ALIASES
            nestingDepthLimit = MAX_YAML_NESTING_DEPTH
            codePointLimit = MAX_YAML_CODE_POINTS
        }
        val value = Yaml(SafeConstructor(options)).load<Any?>(text)
        require(value is Map<*, *>) { "YAML root must be a mapping" }
        require(value.keys.all { it is String }) { "YAML root keys must be strings" }

        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }
}
