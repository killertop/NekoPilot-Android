package libcore

import (
	"encoding/json"
	"testing"
)

func TestBuildExternalPluginConfigUsesMappedEndpoint(t *testing.T) {
	profile := `{"serverAddress":"origin.example","serverPort":443,"password":"p","sni":"","type":"ws","host":"cdn.example","path":"/ws","encryption":"none"}`
	encoded, err := BuildExternalPluginConfig("trojan-go", profile, 1080, "127.0.0.1", 32000, 0, 0, "")
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err = json.Unmarshal([]byte(encoded), &config); err != nil {
		t.Fatal(err)
	}
	if config["remote_addr"] != "127.0.0.1" || config["remote_port"] != float64(32000) {
		t.Fatalf("mapped endpoint lost: %#v", config)
	}
	if config["ssl"].(map[string]any)["sni"] != "origin.example" {
		t.Fatalf("SNI fallback lost: %#v", config["ssl"])
	}
}

func TestBuildNaivePluginPreservesResolverMapping(t *testing.T) {
	profile := `{"serverAddress":"origin.example","serverPort":443,"proto":"https","username":"u","password":"p","sni":"cdn.example"}`
	encoded, err := BuildExternalPluginConfig("naive", profile, 1080, "127.0.0.1", 32000, 0, 0, "")
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err = json.Unmarshal([]byte(encoded), &config); err != nil {
		t.Fatal(err)
	}
	if config["host-resolver-rules"] != "MAP cdn.example 127.0.0.1" {
		t.Fatalf("unexpected resolver mapping: %#v", config)
	}
}

func TestBuildExternalPluginConfigAllKinds(t *testing.T) {
	cases := []struct{ kind, profile, certificate string }{
		{"mieru", `{"serverAddress":"m.example","serverPort":443,"protocol":"TCP","username":"u","password":"p"}`, ""},
		{"hysteria", `{"protocolVersion":1,"serverAddress":"h.example","serverPort":443,"serverPorts":"443","authPayloadType":1,"authPayload":"p","caText":"certificate"}`, "/tmp/hysteria.ca"},
	}
	for _, test := range cases {
		encoded, err := BuildExternalPluginConfig(test.kind, test.profile, 1080, "127.0.0.1", 32000, 0, 0, test.certificate)
		if err != nil {
			t.Fatalf("%s: %v", test.kind, err)
		}
		var config map[string]any
		if err = json.Unmarshal([]byte(encoded), &config); err != nil {
			t.Fatalf("%s: %v", test.kind, err)
		}
		if test.kind == "hysteria" && config["ca"] != test.certificate {
			t.Fatalf("certificate path lost: %#v", config)
		}
	}
}
