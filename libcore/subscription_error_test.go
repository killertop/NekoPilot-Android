package libcore

import (
	"strings"
	"testing"
)

func TestSanitizeSubscriptionError(t *testing.T) {
	link := "https://user:password@example.com/private/account/token?key=top-secret"
	got := SanitizeSubscriptionError("GET "+link+": timeout", link, "update failed")
	if got != "GET https://example.com/…: timeout" {
		t.Fatalf("unexpected known-link sanitization: %q", got)
	}
	redirect := SanitizeSubscriptionError(
		"redirected to https://cdn.example.net/download/opaque-token?signature=secret and failed",
		"https://example.com/subscription",
		"update failed",
	)
	if redirect != "redirected to https://cdn.example.net/… and failed" {
		t.Fatalf("unexpected redirect sanitization: %q", redirect)
	}
	if got := SanitizeSubscriptionError("  ", "", "update failed"); got != "update failed" {
		t.Fatalf("fallback was not preserved: %q", got)
	}
	long := SanitizeSubscriptionError(strings.Repeat("猫", maxSubscriptionErrorRunes+20), "", "fallback")
	if len([]rune(long)) != maxSubscriptionErrorRunes {
		t.Fatalf("sanitized error was not bounded: %d", len([]rune(long)))
	}
}
