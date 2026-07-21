package libcore

import (
	"net/url"
	"regexp"
	"strings"
)

const maxSubscriptionErrorRunes = 500

var subscriptionHTTPURLPattern = regexp.MustCompile(`(?i)https?://[^\s\"'<>]+`)

func safeSubscriptionOrigin(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil || parsed == nil {
		return "subscription source"
	}
	scheme := strings.ToLower(parsed.Scheme)
	if (scheme != "http" && scheme != "https") || parsed.Host == "" {
		return "subscription source"
	}
	return scheme + "://" + parsed.Host + "/…"
}

func trimURLTrailingPunctuation(value string) (string, string) {
	index := len(value)
	for index > 0 && strings.ContainsRune(".,;:)]}", rune(value[index-1])) {
		index--
	}
	return value[:index], value[index:]
}

// SanitizeSubscriptionError removes credentials, paths, query parameters, and opaque tokens from
// provider errors before Android displays them. The localized fallback remains owned by Kotlin.
func SanitizeSubscriptionError(message, subscriptionLink, fallback string) string {
	sanitized := strings.TrimSpace(message)
	if sanitized == "" {
		sanitized = fallback
	}
	if link := strings.TrimSpace(subscriptionLink); link != "" {
		sanitized = strings.ReplaceAll(sanitized, link, safeSubscriptionOrigin(link))
	}
	sanitized = subscriptionHTTPURLPattern.ReplaceAllStringFunc(sanitized, func(match string) string {
		address, trailing := trimURLTrailingPunctuation(match)
		return safeSubscriptionOrigin(address) + trailing
	})
	runes := []rune(sanitized)
	if len(runes) > maxSubscriptionErrorRunes {
		sanitized = string(runes[:maxSubscriptionErrorRunes])
	}
	return sanitized
}
