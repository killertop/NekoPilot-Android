package libcore

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestNormalizeRulePorts(t *testing.T) {
	encoded, err := NormalizeRulePorts("53, 443, 1000:2000, 0, 70000, invalid, 2000:1000, 443")
	if err != nil {
		t.Fatal(err)
	}
	var result rulePortsResult
	if err = json.Unmarshal([]byte(encoded), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Ports) != 2 || result.Ports[0] != 53 || result.Ports[1] != 443 {
		t.Fatalf("unexpected ports: %#v", result.Ports)
	}
	if len(result.Ranges) != 1 || result.Ranges[0] != "1000:2000" {
		t.Fatalf("unexpected ranges: %#v", result.Ranges)
	}
}

func TestParseWireGuardConfigMultiplePeersAndIPv6(t *testing.T) {
	input := `[Interface]
Address = 10.0.0.2/32, fd00::2/128
PrivateKey = private
MTU = 1380

[Peer]
PublicKey = public1
PresharedKey = shared
Endpoint = vpn.example.com:51820

[Peer]
PublicKey = public2
Endpoint = [2001:db8::1]:443
`
	encoded, err := ParseWireGuardConfig(input)
	if err != nil {
		t.Fatal(err)
	}
	var profiles []wireGuardProfile
	if err = json.Unmarshal([]byte(encoded), &profiles); err != nil {
		t.Fatal(err)
	}
	if len(profiles) != 2 {
		t.Fatalf("unexpected profiles: %#v", profiles)
	}
	if profiles[0].LocalAddress != "10.0.0.2/32\nfd00::2/128" || profiles[0].MTU != 1380 {
		t.Fatalf("unexpected interface: %#v", profiles[0])
	}
	if profiles[1].ServerAddress != "2001:db8::1" || profiles[1].ServerPort != 443 {
		t.Fatalf("unexpected IPv6 endpoint: %#v", profiles[1])
	}
}

func TestParseWireGuardConfigRejectsUnsafeInput(t *testing.T) {
	if _, err := ParseWireGuardConfig("Address = 10.0.0.2/32"); err == nil {
		t.Fatal("expected property-before-section error")
	}
	if _, err := ParseWireGuardConfig(strings.Repeat("x", maxPortableConfigBytes+1)); err == nil {
		t.Fatal("expected size limit error")
	}
}

func TestYAMLToJSON(t *testing.T) {
	encoded, err := YAMLToJSON("server: example.com:443\nauth: secret\ntls:\n  insecure: true\n")
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err = json.Unmarshal([]byte(encoded), &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded["server"] != "example.com:443" || decoded["auth"] != "secret" {
		t.Fatalf("unexpected YAML conversion: %#v", decoded)
	}
	if _, err = YAMLToJSON("server: one\n---\nserver: two\n"); err == nil {
		t.Fatal("expected multiple YAML documents to be rejected")
	}
}

func TestParseHysteriaLinks(t *testing.T) {
	legacy, err := ParseHysteriaLink("hysteria://example.com:8443?mport=8443-8445&auth=secret&peer=sni.example&insecure=1&upmbps=20&protocol=faketcp#Legacy")
	if err != nil {
		t.Fatal(err)
	}
	var h1 hysteriaLink
	if err = json.Unmarshal([]byte(legacy), &h1); err != nil {
		t.Fatal(err)
	}
	if h1.ProtocolVersion != 1 || h1.ServerPorts != "8443-8445" || h1.AuthPayload != "secret" || h1.Protocol != 1 {
		t.Fatalf("unexpected Hysteria 1 link: %#v", h1)
	}
	hy2, err := ParseHysteriaLink("hy2://user:pass@[2001:db8::1]:443/?sni=edge.example&obfs-password=x#IPv6")
	if err != nil {
		t.Fatal(err)
	}
	var h2 hysteriaLink
	if err = json.Unmarshal([]byte(hy2), &h2); err != nil {
		t.Fatal(err)
	}
	if h2.ProtocolVersion != 2 || h2.ServerAddress != "2001:db8::1" || h2.AuthPayload != "user:pass" || h2.SNI != "edge.example" {
		t.Fatalf("unexpected Hysteria 2 link: %#v", h2)
	}
}

func TestValidateSingBoxConfigRejectsUnknownFields(t *testing.T) {
	if err := ValidateSingBoxConfig(`{"outbounds":[{"type":"direct","tag":"direct"}]}`); err != nil {
		t.Fatalf("expected valid config: %v", err)
	}
	if err := ValidateSingBoxConfig(`{"unknown":true}`); err == nil {
		t.Fatal("expected unknown field to be rejected")
	}
}

func TestZlibRoundTripAndLimits(t *testing.T) {
	input := []byte(strings.Repeat("NekoPilot", 1024))
	compressed, err := ZlibCompress(input, 9)
	if err != nil {
		t.Fatal(err)
	}
	decompressed, err := ZlibDecompress(compressed, int64(len(input)))
	if err != nil {
		t.Fatal(err)
	}
	if string(decompressed) != string(input) {
		t.Fatal("zlib round trip mismatch")
	}
	if _, err = ZlibDecompress(compressed, 100); err == nil {
		t.Fatal("expected output limit error")
	}
	if _, err = ZlibDecompress(append(compressed, 0), int64(len(input))); err == nil {
		t.Fatal("expected trailing data error")
	}
	if _, err = ZlibDecompress(compressed[:len(compressed)-1], int64(len(input))); err == nil {
		t.Fatal("expected truncation error")
	}
}

func TestQueryStatsPackedLayout(t *testing.T) {
	result, err := new(BoxInstance).QueryStatsPacked("proxy\nbypass")
	if err != nil {
		t.Fatal(err)
	}
	if len(result) != 32 {
		t.Fatalf("unexpected packed stats length: %d", len(result))
	}
	if empty, err := new(BoxInstance).QueryStatsPacked(""); err != nil || len(empty) != 0 {
		t.Fatalf("unexpected empty stats result: %d, %v", len(empty), err)
	}
}
