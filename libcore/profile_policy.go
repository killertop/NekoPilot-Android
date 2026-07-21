package libcore

import "strings"

// HysteriaServerHasMultiplePorts identifies legacy Hysteria addresses whose port suffix
// contains a range or a comma-separated list. It is used while deserializing historical
// Android profiles before the address and port fields were stored separately.
func HysteriaServerHasMultiplePorts(value string) bool {
	separator := strings.LastIndexByte(value, ':')
	if separator < 0 || separator == len(value)-1 {
		return false
	}
	ports := value[separator+1:]
	return strings.ContainsAny(ports, "-,")
}

// HysteriaNeedsExternal reports whether the legacy Hysteria 1 transport still requires the
// external compatibility process. UDP (0) is handled directly by sing-box.
func HysteriaNeedsExternal(protocol int32) bool {
	return protocol != 0
}

const (
	connectionFailureOther int32 = iota
	connectionFailureTimeout
	connectionFailureReset
)

// ClassifyConnectionFailure centralizes transport-error semantics beside the Go proxy core.
// Android maps the stable integer result to localized presentation text.
func ClassifyConnectionFailure(message string) int32 {
	normalized := strings.ToLower(message)
	if strings.Contains(normalized, "timeout") || strings.Contains(normalized, "deadline") {
		return connectionFailureTimeout
	}
	if strings.Contains(normalized, "refused") ||
		strings.Contains(normalized, "closed pipe") ||
		strings.Contains(normalized, "closed network connection") ||
		strings.Contains(normalized, "connection reset") ||
		normalized == "eof" {
		return connectionFailureReset
	}
	return connectionFailureOther
}
