package libcore

import (
	"encoding/json"
	"strings"
	"testing"
)

func decodeSubscriptionUserInfo(t *testing.T, value string) subscriptionUserInfoResponse {
	t.Helper()
	raw, err := ParseSubscriptionUserInfo(value)
	if err != nil {
		t.Fatal(err)
	}
	var response subscriptionUserInfoResponse
	if err := json.Unmarshal([]byte(raw), &response); err != nil {
		t.Fatal(err)
	}
	return response
}

func TestSubscriptionUserInfoIsBoundedAndCanonical(t *testing.T) {
	response := decodeSubscriptionUserInfo(t,
		"upload=12; download=34; total=100; expire=1893456000; title=unsafe; upload=-1")
	if response.Sanitized != "upload=12; download=34; total=100; expire=1893456000" {
		t.Fatalf("unexpected canonical value: %q", response.Sanitized)
	}
	if response.Upload == nil || *response.Upload != 12 || response.Expire == nil || *response.Expire != 1893456000 {
		t.Fatalf("unexpected parsed response: %#v", response)
	}

	response = decodeSubscriptionUserInfo(t,
		"upload=99999999999999999999; download=1\r\nInjected: true; total=1024")
	if response.Sanitized != "total=1024" {
		t.Fatalf("overflow or injected value was accepted: %q", response.Sanitized)
	}
	response = decodeSubscriptionUserInfo(t, "upload=1; upload=2; total=3")
	if response.Sanitized != "upload=1; total=3" {
		t.Fatalf("duplicate handling changed: %q", response.Sanitized)
	}
	response = decodeSubscriptionUserInfo(t, strings.Repeat("total=1;", 600))
	if response.Sanitized != "" {
		t.Fatal("oversized header was accepted")
	}
}

func TestSubscriptionDisplayNameParsing(t *testing.T) {
	tests := []struct {
		header string
		want   string
	}{
		{"attachment; filename*=UTF-8''%E6%88%91%E7%9A%84%20%20%E6%9C%BA%E5%9C%BA", "我的 机场"},
		{`attachment; filename="Neko subscription"`, "Neko subscription"},
		{"attachment; filename*=UTF-8''bad%ZZname", ""},
		{`attachment; filename="` + strings.Repeat("猫", 120) + `"`, strings.Repeat("猫", 80)},
		{"attachment; filename*=UTF-8''safe%20name; filename=backup", "safe name"},
		{"attachment; filename*=UTF-8''bad%ZZ; filename=backup", "backup"},
	}
	for _, test := range tests {
		if got := ParseSubscriptionDisplayName(test.header); got != test.want {
			t.Fatalf("ParseSubscriptionDisplayName(%q) = %q, want %q", test.header, got, test.want)
		}
	}
	if got := ParseSubscriptionDisplayName(strings.Repeat("x", 4097)); got != "" {
		t.Fatal("oversized content-disposition was accepted")
	}
}
