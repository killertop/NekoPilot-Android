package libcore

import (
	"bytes"
	"net/url"
	"testing"
)

func TestBase64CompatibilityRoundTrip(t *testing.T) {
	payload := []byte{0, 1, 2, 0xfb, 0xff, 0x7f}
	encoded := Base64EncodeURLSafe(payload)
	decoded, err := Base64DecodeFlexible(encoded)
	if err != nil || !bytes.Equal(decoded, payload) {
		t.Fatalf("URL-safe base64 round trip failed: %x, %v", decoded, err)
	}
	for _, value := range []string{"aGVsbG8=", "aGVsbG8", "aGVsbG8_"} {
		if _, err := Base64DecodeFlexible(value); err != nil {
			t.Fatalf("valid base64 %q was rejected: %v", value, err)
		}
	}
	if _, err := Base64DecodeFlexible("not base64 !"); err == nil {
		t.Fatal("invalid base64 was accepted")
	}
}

func TestNumericIPAddressValidation(t *testing.T) {
	for _, value := range []string{"127.0.0.1", "2001:db8::1", "[2001:db8::1]", "[2001:db8::1]:443"} {
		if !IsIPAddress(value) {
			t.Errorf("valid numeric address %q was rejected", value)
		}
	}
	for _, value := range []string{"example.com", "999.1.1.1", "127.0.0.1:80", "2001:db8::zz"} {
		if IsIPAddress(value) {
			t.Errorf("invalid numeric address %q was accepted", value)
		}
	}
	if !IsIPv6Address("[2001:db8::1]:443") || IsIPv6Address("127.0.0.1") {
		t.Fatal("IPv6 classification failed")
	}
}

func TestScannedSubscriptionLink(t *testing.T) {
	appLink := "sn://subscription?encoded-payload"
	if got := ScannedSubscriptionLink("  " + appLink + "  "); got != appLink {
		t.Fatalf("app subscription changed: %q", got)
	}
	clashLink := "clash://install-config?url=https%3A%2F%2Fexample.com%2Fsub"
	if got := ScannedSubscriptionLink(clashLink); got != clashLink {
		t.Fatalf("Clash subscription changed: %q", got)
	}
	source := "https://example.com/sub?token=a+b&client=android"
	converted := ScannedSubscriptionLink(source)
	parsed, err := url.Parse(converted)
	if err != nil || parsed.Query().Get("url") != source {
		t.Fatalf("HTTP subscription conversion failed: %q, %v", converted, err)
	}
	for _, value := range []string{
		"vless://id@example.com:443",
		"clash://unsupported",
		"https://example.com/a\nhttps://example.com/b",
		"not a QR import link",
	} {
		if got := ScannedSubscriptionLink(value); got != "" {
			t.Errorf("non-subscription QR payload %q produced %q", value, got)
		}
	}
}
