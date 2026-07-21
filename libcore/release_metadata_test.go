package libcore

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestParseAppRelease(t *testing.T) {
	input := `{
		"tag_name":"v1.5.2",
		"body":"Fix connection stability\r\n\r\n- Improve DNS fallback",
		"html_url":"https://github.com/killertop/NekoPilot-Android/releases/tag/v1.5.2"
	}`
	encoded, err := ParseAppRelease(input, "killertop/NekoPilot-Android")
	if err != nil {
		t.Fatal(err)
	}
	var release appReleaseMetadata
	if err := json.Unmarshal([]byte(encoded), &release); err != nil {
		t.Fatal(err)
	}
	if release.Version != "1.5.2" || release.Notes != "Fix connection stability\n\n- Improve DNS fallback" {
		t.Fatalf("unexpected app release metadata: %#v", release)
	}
	if _, err := ParseAppRelease(
		`{"tag_name":"v1.5.2","html_url":"https://example.com/release"}`,
		"killertop/NekoPilot-Android",
	); err == nil {
		t.Fatal("non-GitHub release page was accepted")
	}
}

func TestIsRemoteVersionNewer(t *testing.T) {
	tests := []struct {
		remote  string
		current string
		want    bool
	}{
		{"v1.10.0", "1.9.9", true},
		{"1.5.2", "1.5.1", true},
		{"1.5.1", "1.5.1-qa", true},
		{"1.5.2", "1.5.2-beta", true},
		{"1.5.1", "1.5.1", false},
		{"1.5.2", "1.5.2+5", false},
		{"1.5.2+9", "1.5.2+5", false},
		{"1.5.0", "1.5.1", false},
		{"not-a-version", "1.5.1", false},
		{strings.Repeat("9", 21) + ".1.0", "1.5.1", false},
	}
	for _, test := range tests {
		if got := IsRemoteVersionNewer(test.remote, test.current); got != test.want {
			t.Errorf("IsRemoteVersionNewer(%q, %q) = %v, want %v", test.remote, test.current, got, test.want)
		}
	}
}

func TestParseRuleAssetRelease(t *testing.T) {
	input := `{
		"tag_name":"20260721",
		"assets":[
			{"name":"geoip.db","browser_download_url":"https://github.com/SagerNet/sing-geoip/releases/download/20260721/geoip.db","size":1024},
			{"name":"geoip.db.sha256sum","browser_download_url":"https://github.com/SagerNet/sing-geoip/releases/download/20260721/geoip.db.sha256sum","size":80}
		]
	}`
	encoded, err := ParseRuleAssetRelease(input, "SagerNet/sing-geoip", geoipDat, 16*1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	var release ruleAssetReleaseMetadata
	if err := json.Unmarshal([]byte(encoded), &release); err != nil {
		t.Fatal(err)
	}
	if release.Tag != "20260721" || release.Size != 1024 || release.DownloadURL == "" || release.ChecksumURL == "" {
		t.Fatalf("unexpected rule release metadata: %#v", release)
	}
	malicious := strings.Replace(input, "https://github.com/SagerNet/sing-geoip/", "https://example.com/", 2)
	if _, err := ParseRuleAssetRelease(malicious, "SagerNet/sing-geoip", geoipDat, 16*1024*1024); err == nil {
		t.Fatal("untrusted rule asset URLs were accepted")
	}
}
