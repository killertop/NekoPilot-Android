package libcore

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRuntimeNativeGRPCTracksSpeedTestTraffic(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/octet-stream")
		_, _ = writer.Write(make([]byte, 1024))
	}))
	defer server.Close()

	instance, err := NewSingBoxInstance(`{
		"log":{"level":"error"},
		"dns":{"servers":[{"type":"local","tag":"dns-local"}],"final":"dns-local"},
		"outbounds":[{"type":"direct","tag":"direct"}]
	}`, nil)
	if err != nil {
		t.Fatalf("create runtime core: %v", err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatalf("start runtime core: %v", err)
	}

	encoded, err := UrlTestDownload(instance, server.URL, 5_000, 2_048)
	if err != nil {
		t.Fatalf("speed test through runtime proxy: %v", err)
	}
	var result struct {
		Bytes     int64 `json:"bytes"`
		CoreBytes int64 `json:"coreBytes"`
	}
	if err = json.Unmarshal([]byte(encoded), &result); err != nil {
		t.Fatal(err)
	}
	if result.Bytes != 1024 {
		t.Fatalf("unexpected speed-test bytes: %#v", result)
	}
	if result.CoreBytes < result.Bytes {
		t.Fatalf("native gRPC did not observe the proxied traffic: %#v", result)
	}
}

func TestRuntimeConfigRemovesClashServiceBeforeCoreStartup(t *testing.T) {
	prepared, _, err := prepareRuntimeConfig(`{
		"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090"}}
	}`)
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err = json.Unmarshal([]byte(prepared), &config); err != nil {
		t.Fatal(err)
	}
	experimental := config["experimental"].(map[string]any)
	if _, exists := experimental["clash_api"]; exists {
		t.Fatalf("Clash API leaked into runtime config: %#v", experimental)
	}
	if _, exists := experimental["v2ray_api"]; !exists {
		t.Fatalf("native gRPC API is missing: %#v", experimental)
	}
}
