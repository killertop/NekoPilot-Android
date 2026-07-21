package libcore

import (
	"net/url"
	"strconv"
	"strings"

	"golang.org/x/net/idna"
)

const maxSubscriptionURLUTF16Units = 8 * 1024

// CanonicalSubscriptionURL returns an identity key for an HTTP(S) subscription URL. It keeps
// raw credentials, path, and query because they may carry signed provider data, while normalizing
// only scheme, host, default port, empty path, and fragment.
func CanonicalSubscriptionURL(raw string) string {
	if !subscriptionURLWithinLimit(raw) {
		return ""
	}
	trimmed := strings.TrimSpace(raw)
	parsed, err := url.Parse(trimmed)
	if err != nil {
		return ""
	}
	scheme := strings.ToLower(parsed.Scheme)
	if (scheme != "http" && scheme != "https") || parsed.Host == "" {
		return ""
	}

	host := parsed.Hostname()
	if host == "" {
		return ""
	}
	if strings.Contains(host, ":") {
		host = strings.ToLower(host)
	} else {
		host = strings.TrimRight(host, ".")
		host, err = idna.Lookup.ToASCII(host)
		if err != nil || host == "" {
			return ""
		}
		host = strings.ToLower(host)
	}

	port := parsed.Port()
	if port != "" {
		parsedPort, parseErr := strconv.ParseUint(port, 10, 31)
		if parseErr != nil {
			return ""
		}
		port = strconv.FormatUint(parsedPort, 10)
	}
	if (scheme == "http" && port == "80") || (scheme == "https" && port == "443") {
		port = ""
	}
	authorityHost := host
	if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
		authorityHost = "[" + host + "]"
	}
	userinfo := rawSubscriptionUserInfo(trimmed)
	authority := userinfo + authorityHost
	if port != "" {
		authority += ":" + port
	}

	path := parsed.EscapedPath()
	if path == "" {
		path = "/"
	}
	canonical := scheme + "://" + authority + path
	if parsed.ForceQuery || parsed.RawQuery != "" {
		canonical += "?" + parsed.RawQuery
	}
	return canonical
}

// SameSubscriptionURL compares canonical keys and deliberately falls back to trimmed raw values
// when either URL is invalid, so two unrelated invalid inputs never collide through an empty key.
func SameSubscriptionURL(first, second string) bool {
	if !subscriptionURLWithinLimit(first) || !subscriptionURLWithinLimit(second) {
		return false
	}
	firstKey := CanonicalSubscriptionURL(first)
	secondKey := CanonicalSubscriptionURL(second)
	if firstKey != "" && secondKey != "" {
		return firstKey == secondKey
	}
	return strings.TrimSpace(first) == strings.TrimSpace(second)
}

func subscriptionURLWithinLimit(raw string) bool {
	return len(raw) <= maxSubscriptionURLUTF16Units*4 &&
		utf16Units(raw) <= maxSubscriptionURLUTF16Units
}

func rawSubscriptionUserInfo(raw string) string {
	schemeEnd := strings.Index(raw, "://")
	if schemeEnd < 0 {
		return ""
	}
	authority := raw[schemeEnd+3:]
	if end := strings.IndexAny(authority, "/?#"); end >= 0 {
		authority = authority[:end]
	}
	if separator := strings.LastIndexByte(authority, '@'); separator >= 0 {
		return authority[:separator+1]
	}
	return ""
}
