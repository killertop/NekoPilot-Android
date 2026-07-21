package libcore

import (
	"encoding/json"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

const (
	maxSubscriptionUserInfoUTF16Units           = 4 * 1024
	maxSubscriptionContentDispositionUTF16Units = 4 * 1024
	maxSubscriptionDisplayNameCodePoints        = 80
)

var (
	subscriptionExtendedFilename = regexp.MustCompile(
		`(?i)(^|;)\s*filename\*\s*=\s*([^;]+)`,
	)
	subscriptionRegularFilename = regexp.MustCompile(
		`(?i)(^|;)\s*filename\s*=\s*(?:"([^"]*)"|([^;]*))`,
	)
	subscriptionWhitespace = regexp.MustCompile(`[\t\n\v\f\r ]+`)
)

type subscriptionUserInfoResponse struct {
	Sanitized string `json:"sanitized"`
	Upload    *int64 `json:"upload,omitempty"`
	Download  *int64 `json:"download,omitempty"`
	Total     *int64 `json:"total,omitempty"`
	Expire    *int64 `json:"expire,omitempty"`
}

// ParseSubscriptionUserInfo validates an untrusted subscription-userinfo response header and
// returns both the canonical persisted value and its numeric fields. Kotlin owns only the JSON
// adaptation; length limits, duplicate handling, and accepted keys remain deterministic in Go.
func ParseSubscriptionUserInfo(headerValue string) (string, error) {
	values := parseSubscriptionUserInfoValues(headerValue)
	response := subscriptionUserInfoResponse{
		Sanitized: canonicalSubscriptionUserInfo(values),
		Upload:    optionalSubscriptionValue(values, "upload"),
		Download:  optionalSubscriptionValue(values, "download"),
		Total:     optionalSubscriptionValue(values, "total"),
		Expire:    optionalSubscriptionValue(values, "expire"),
	}
	encoded, err := json.Marshal(response)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

// ParseSubscriptionDisplayName extracts and sanitizes a bounded filename from an untrusted
// Content-Disposition header. An empty result means the header did not contain a safe name.
func ParseSubscriptionDisplayName(contentDisposition string) string {
	if strings.TrimSpace(contentDisposition) == "" ||
		utf16Units(contentDisposition) > maxSubscriptionContentDispositionUTF16Units {
		return ""
	}

	extended, extendedPresent := extractExtendedSubscriptionFilename(contentDisposition)
	regular := extractRegularSubscriptionFilename(contentDisposition)
	if extendedPresent {
		return sanitizeSubscriptionDisplayName(extended)
	}
	return sanitizeSubscriptionDisplayName(regular)
}

func parseSubscriptionUserInfoValues(headerValue string) map[string]int64 {
	if utf16Units(headerValue) > maxSubscriptionUserInfoUTF16Units {
		return nil
	}
	supported := map[string]struct{}{
		"upload": {}, "download": {}, "total": {}, "expire": {},
	}
	values := make(map[string]int64, len(supported))
	for _, item := range strings.Split(headerValue, ";") {
		separator := strings.IndexByte(item, '=')
		if separator <= 0 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(item[:separator]))
		value := strings.TrimSpace(item[separator+1:])
		if _, ok := supported[key]; !ok {
			continue
		}
		if _, duplicate := values[key]; duplicate || len(value) < 1 || len(value) > 19 {
			continue
		}
		parsed, err := strconv.ParseInt(value, 10, 64)
		if err == nil && parsed >= 0 {
			values[key] = parsed
		}
	}
	return values
}

func canonicalSubscriptionUserInfo(values map[string]int64) string {
	parts := make([]string, 0, 4)
	for _, key := range []string{"upload", "download", "total", "expire"} {
		if value, ok := values[key]; ok {
			parts = append(parts, key+"="+strconv.FormatInt(value, 10))
		}
	}
	return strings.Join(parts, "; ")
}

func optionalSubscriptionValue(values map[string]int64, key string) *int64 {
	value, ok := values[key]
	if !ok {
		return nil
	}
	return &value
}

func extractExtendedSubscriptionFilename(contentDisposition string) (string, bool) {
	match := subscriptionExtendedFilename.FindStringSubmatch(contentDisposition)
	if len(match) < 3 {
		return "", false
	}
	encoded := strings.Trim(strings.TrimSpace(match[2]), `"`)
	delimiter := strings.Index(encoded, "''")
	if delimiter < 0 || strings.TrimSpace(encoded[delimiter+2:]) == "" {
		return "", false
	}
	decoded, err := url.QueryUnescape(encoded[delimiter+2:])
	if err != nil {
		return "", false
	}
	return decoded, true
}

func extractRegularSubscriptionFilename(contentDisposition string) string {
	match := subscriptionRegularFilename.FindStringSubmatch(contentDisposition)
	if len(match) < 4 {
		return ""
	}
	if match[2] != "" {
		return match[2]
	}
	return match[3]
}

func sanitizeSubscriptionDisplayName(value string) string {
	cleaned := strings.Map(func(character rune) rune {
		if unicode.IsControl(character) || unicode.Is(unicode.Cf, character) {
			return -1
		}
		return character
	}, value)
	cleaned = strings.TrimSpace(subscriptionWhitespace.ReplaceAllString(cleaned, " "))
	if cleaned == "" {
		return ""
	}
	codePoints := []rune(cleaned)
	if len(codePoints) > maxSubscriptionDisplayNameCodePoints {
		codePoints = codePoints[:maxSubscriptionDisplayNameCodePoints]
	}
	return string(codePoints)
}

func utf16Units(value string) int {
	units := 0
	for _, character := range value {
		units++
		if character > 0xffff {
			units++
		}
	}
	return units
}
