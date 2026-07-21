package libcore

import (
	"encoding/base64"
	"net/netip"
	"net/url"
	"strings"
)

// Base64EncodeURLSafe encodes compatibility payloads without padding or line wrapping.
func Base64EncodeURLSafe(data []byte) string {
	return base64.RawURLEncoding.EncodeToString(data)
}

// Base64DecodeFlexible accepts the standard and URL-safe padded/unpadded forms used by legacy
// Neko/SagerNet exports and subscription payloads.
func Base64DecodeFlexible(value string) ([]byte, error) {
	return decodeBase64String(value)
}

func numericAddress(value string) (netip.Addr, bool) {
	candidate := value
	if strings.HasPrefix(candidate, "[") {
		end := strings.IndexByte(candidate, ']')
		if end <= 1 {
			return netip.Addr{}, false
		}
		candidate = candidate[1:end]
	}
	address, err := netip.ParseAddr(candidate)
	return address, err == nil
}

// IsIPAddress validates a numeric IPv4 or IPv6 host without DNS resolution.
func IsIPAddress(value string) bool {
	_, valid := numericAddress(value)
	return valid
}

// IsIPv6Address validates a numeric IPv6 host, including the bracketed URI representation.
func IsIPv6Address(value string) bool {
	address, valid := numericAddress(value)
	return valid && address.Is6()
}

// ScannedSubscriptionLink returns a canonical subscription deep link for a single QR payload.
// An empty result means the payload must be handled by the normal node/profile importer.
func ScannedSubscriptionLink(text string) string {
	candidate := strings.TrimSpace(text)
	if candidate == "" || strings.ContainsAny(candidate, "\r\n") {
		return ""
	}
	parsed, err := url.Parse(candidate)
	if err != nil {
		return ""
	}
	scheme := strings.ToLower(parsed.Scheme)
	host := parsed.Hostname()
	switch {
	case scheme == "sn" && strings.EqualFold(host, "subscription"):
		return candidate
	case scheme == "clash" && strings.EqualFold(host, "install-config"):
		return candidate
	case (scheme == "https" || scheme == "http") && host != "":
		return "sn://subscription?url=" + url.QueryEscape(candidate)
	default:
		return ""
	}
}
