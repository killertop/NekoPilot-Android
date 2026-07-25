package io.nekohasekai.sagernet.fmt.hysteria

/**
 * Canonical representation of the legacy profile field used for either a single Hysteria port or
 * the official sing-box `server_ports` list. Keeping this parsing in one place prevents the UI,
 * URI importer, and runtime builder from accepting different (or partially malformed) input.
 */
internal sealed interface HysteriaServerPorts {
    val storedValue: String

    data class Single(val port: Int) : HysteriaServerPorts {
        override val storedValue: String = port.toString()
    }

    data class Ranges(val values: List<String>) : HysteriaServerPorts {
        override val storedValue: String = values.joinToString(",")
    }
}

/**
 * Accepts a single port, a comma-separated list of ports, and `start-end` / `start:end` ranges.
 * Ranges are normalized to the colon form required by sing-box. Every supplied list item is
 * validated; a malformed item is never silently omitted.
 */
internal fun parseHysteriaServerPorts(raw: String): HysteriaServerPorts {
    val values = raw.split(',').map(String::trim)
    require(values.isNotEmpty() && values.all(String::isNotEmpty)) {
        "Invalid Hysteria server ports"
    }
    if (values.size == 1 && values.single().all(Char::isDigit)) {
        return HysteriaServerPorts.Single(parseHysteriaPort(values.single()))
    }
    return HysteriaServerPorts.Ranges(values.map(::normalizeHysteriaPortRange))
}

private fun normalizeHysteriaPortRange(value: String): String {
    if (value.all(Char::isDigit)) return parseHysteriaPort(value).toString()
    val separator = when {
        value.count { it == ':' } == 1 && '-' !in value -> ':'
        value.count { it == '-' } == 1 && ':' !in value -> '-'
        else -> error("Invalid Hysteria server port range: $value")
    }
    val (startText, endText) = value.split(separator, limit = 2).map(String::trim)
    val start = parseHysteriaPort(startText)
    val end = parseHysteriaPort(endText)
    require(start <= end) { "Invalid Hysteria server port range: $value" }
    return "$start:$end"
}

private fun parseHysteriaPort(value: String): Int = value.toIntOrNull()
    ?.takeIf { it in 1..65_535 }
    ?: error("Invalid Hysteria server port: $value")
