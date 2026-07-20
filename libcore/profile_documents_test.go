package libcore

import (
	"encoding/base64"
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestParseProfileDocumentClash(t *testing.T) {
	input := `
global-client-fingerprint: chrome
proxies:
  - name: reality
    type: vless
    server: example.com
    port: 443
    uuid: test-id
    network: grpc
    tls: true
    servername: sni.example.com
    reality-opts:
      public-key: test-public-key
      short-id: ab
    grpc-opts:
      grpc-service-name: tunnel
  - name: ss
    type: ss
    server: 1.2.3.4
    port: 8388
    cipher: aes-256-gcm
    password: secret
`
	encoded, err := ParseProfileDocument(input)
	if err != nil {
		t.Fatal(err)
	}
	profiles := decodeProfiles(t, encoded)
	if len(profiles) != 2 {
		t.Fatalf("unexpected profiles: %s", encoded)
	}
	if profiles[0]["realityPubKey"] != "test-public-key" || profiles[0]["path"] != "tunnel" {
		t.Fatalf("unexpected VLESS: %#v", profiles[0])
	}
	if profiles[1]["kind"] != "ss" || profiles[1]["method"] != "aes-256-gcm" {
		t.Fatalf("unexpected SS: %#v", profiles[1])
	}
}

func TestParseProfileDocumentClashCompatibilityOptions(t *testing.T) {
	input := `proxies:
  - name: ss-obfs
    type: ss
    server: 1.2.3.4
    port: 8388
    cipher: aes-256-gcm
    password: secret
    plugin: obfs
    plugin-opts: {mode: http, host: cdn.example}
  - name: http-upgrade
    type: vless
    server: vless.example
    port: 443
    uuid: id
    network: ws
    ws-opts: {path: /upgrade, v2ray-http-upgrade: true}
  - name: hysteria-window
    type: hysteria
    server: hy.example
    port: 443
    auth-str: secret
    alpn: [hysteria]
    recv-window-conn: 1048576
    recv-window: 4194304
    disable-mtu-discovery: true`
	encoded, err := ParseProfileDocument(input)
	if err != nil {
		t.Fatal(err)
	}
	profiles := decodeProfiles(t, encoded)
	if len(profiles) != 3 {
		t.Fatalf("unexpected profiles: %s", encoded)
	}
	if profiles[0]["plugin"] != "obfs-local;obfs=http;obfs-host=cdn.example" {
		t.Fatalf("Shadowsocks plugin options lost: %#v", profiles[0])
	}
	if profiles[1]["type"] != "httpupgrade" {
		t.Fatalf("HTTP upgrade transport lost: %#v", profiles[1])
	}
	if profiles[2]["streamReceiveWindow"] != float64(1048576) || profiles[2]["connectionReceiveWindow"] != float64(4194304) || profiles[2]["disableMtuDiscovery"] != true {
		t.Fatalf("Hysteria compatibility options lost: %#v", profiles[2])
	}
}

func TestParseProfileDocumentClashExtendedTypes(t *testing.T) {
	input := `proxies:
  - {name: any, type: anytls, server: any.example, port: 443, password: secret}
  - {name: hy, type: hysteria2, server: hy.example, port: 443, password: secret}
  - {name: tuic, type: tuic, server: tuic.example, port: 443, uuid: id, password: secret}
  - {name: wg, type: wireguard, server: wg.example, port: 51820, ip: 10.0.0.2/32, ipv6: 2001:db8::2/128, private-key: private, public-key: public}
  - {name: ssh, type: ssh, server: ssh.example, port: 22, username: root, password: secret}
  - {name: mieru, type: mieru, server: mieru.example, port: 443, transport: tcp, username: user, password: secret}
  - {name: stls, type: shadowtls, server: stls.example, port: 443, version: 3, password: secret, sni: edge.example}`
	encoded, err := ParseProfileDocument(input)
	if err != nil {
		t.Fatal(err)
	}
	profiles := decodeProfiles(t, encoded)
	if len(profiles) != 7 {
		t.Fatalf("unexpected profiles: %s", encoded)
	}
	if profiles[3]["localAddress"] != "10.0.0.2/32\n2001:db8::2/128" {
		t.Fatalf("wireguard addresses lost: %#v", profiles[3])
	}
}

func TestParseProfileDocumentClashSkipsUnsupportedAndInvalidEntries(t *testing.T) {
	input := `proxies:
  - {name: socks, type: socks5, server: 127.0.0.1, port: 1080}
  - {name: unsupported, type: snell, server: snell.example, port: 443}
  - {name: missing-server, type: ss, port: 8388, cipher: aes-256-gcm, password: secret}
  - malformed-entry
  - {name: ss, type: ss, server: 1.2.3.4, port: 8388, cipher: aes-256-gcm, password: secret}`

	encoded, err := ParseProfileDocument(input)
	if err != nil {
		t.Fatal(err)
	}
	profiles := decodeProfiles(t, encoded)
	if len(profiles) != 2 {
		t.Fatalf("expected two supported profiles, got: %s", encoded)
	}
	if profiles[0]["name"] != "socks" || profiles[0]["kind"] != "socks" {
		t.Fatalf("unexpected first profile: %#v", profiles[0])
	}
	if profiles[1]["name"] != "ss" || profiles[1]["kind"] != "ss" {
		t.Fatalf("unexpected second profile: %#v", profiles[1])
	}
}

func TestParseSubscriptionDocumentReportsSkippedClashEntries(t *testing.T) {
	input := `proxies:
  - {name: socks, type: socks5, server: 127.0.0.1, port: 1080}
  - {name: unsupported, type: snell, server: snell.example, port: 443}
  - {name: missing-server, type: ss, port: 8388}
  - malformed-entry`

	encoded, err := ParseSubscriptionDocument(input)
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		Profiles          []map[string]any `json:"profiles"`
		SkippedNames      []string         `json:"skippedNames"`
		HasUnnamedSkipped bool             `json:"hasUnnamedSkipped"`
	}
	if err = json.Unmarshal([]byte(encoded), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Profiles) != 1 || result.Profiles[0]["name"] != "socks" {
		t.Fatalf("unexpected parsed profiles: %s", encoded)
	}
	if !reflect.DeepEqual(result.SkippedNames, []string{"unsupported", "missing-server"}) {
		t.Fatalf("unexpected skipped names: %#v", result.SkippedNames)
	}
	if !result.HasUnnamedSkipped {
		t.Fatal("expected unnamed malformed entry to be reported")
	}
}

func TestParseSubscriptionDocumentMarksCompleteDocuments(t *testing.T) {
	encoded, err := ParseSubscriptionDocument("socks5://127.0.0.1:1080#node")
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		Profiles          []map[string]any `json:"profiles"`
		SkippedNames      []string         `json:"skippedNames"`
		HasUnnamedSkipped bool             `json:"hasUnnamedSkipped"`
	}
	if err = json.Unmarshal([]byte(encoded), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Profiles) != 1 || len(result.SkippedNames) != 0 || result.HasUnnamedSkipped {
		t.Fatalf("unexpected subscription metadata: %s", encoded)
	}
}

func TestParseSubscriptionDocumentRejectsOversizedInputBeforeYaml(t *testing.T) {
	_, err := ParseSubscriptionDocument(strings.Repeat("x", maxPortableConfigBytes+1))
	if err == nil || !strings.Contains(err.Error(), "too large") {
		t.Fatalf("expected bounded input error, got %v", err)
	}
}

func TestParseSubscriptionDocumentKeepsSkippedMetadataThroughBase64(t *testing.T) {
	input := `proxies:
  - {name: socks, type: socks5, server: 127.0.0.1, port: 1080}
  - {name: missing-server, type: ss, port: 8388}`
	encodedInput := base64.RawStdEncoding.EncodeToString([]byte(input))
	encoded, err := ParseSubscriptionDocument(encodedInput)
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		SkippedNames []string `json:"skippedNames"`
	}
	if err = json.Unmarshal([]byte(encoded), &result); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(result.SkippedNames, []string{"missing-server"}) {
		t.Fatalf("base64 path lost skipped metadata: %s", encoded)
	}
}

func TestParseSubscriptionDocumentProtectsPartialLinkLists(t *testing.T) {
	input := "invalid-entry\nsocks5://127.0.0.1:1080#valid"
	encoded, err := ParseSubscriptionDocument(input)
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		Profiles          []map[string]any `json:"profiles"`
		HasUnnamedSkipped bool             `json:"hasUnnamedSkipped"`
	}
	if err = json.Unmarshal([]byte(encoded), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Profiles) != 1 || !result.HasUnnamedSkipped {
		t.Fatalf("partial link metadata was not preserved: %s", encoded)
	}
}

func TestParseProfileDocumentClashRejectsAllInvalidEntriesClearly(t *testing.T) {
	input := `proxies:
  - {name: unsupported, type: snell, server: snell.example, port: 443}
  - {name: missing-server, type: ss, port: 8388}
  - malformed-entry`

	_, err := ParseProfileDocument(input)
	if err == nil {
		t.Fatal("expected an error for an all-invalid Clash subscription")
	}
	if !strings.Contains(err.Error(), "Clash subscription contains no supported profiles") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestParseProfileDocumentSingBoxAndBase64(t *testing.T) {
	encoded, err := ParseProfileDocument(`{"outbounds":[{"type":"direct","tag":"direct"},{"type":"vless","tag":"node","server":"example.com","server_port":443,"uuid":"id"}]}`)
	if err != nil {
		t.Fatal(err)
	}
	profiles := decodeProfiles(t, encoded)
	if len(profiles) != 1 || profiles[0]["kind"] != "config" || profiles[0]["name"] != "node" {
		t.Fatalf("unexpected sing-box profiles: %s", encoded)
	}
	links := base64.RawStdEncoding.EncodeToString([]byte("socks5://user:pass@127.0.0.1:1080"))
	encoded, err = ParseProfileDocument(links)
	if err != nil {
		t.Fatal(err)
	}
	profiles = decodeProfiles(t, encoded)
	if len(profiles) != 1 || profiles[0]["kind"] != "socks" {
		t.Fatalf("unexpected encoded profiles: %s", encoded)
	}
}

func TestParseProfileDocumentSIP008BeforeGenericOutbound(t *testing.T) {
	encoded, err := ParseProfileDocument(`{"server":"1.2.3.4","server_port":8388,"method":"aes-256-gcm","password":"secret","remarks":"SIP008"}`)
	if err != nil {
		t.Fatal(err)
	}
	profiles := decodeProfiles(t, encoded)
	if len(profiles) != 1 || profiles[0]["kind"] != "ss" || profiles[0]["method"] != "aes-256-gcm" {
		t.Fatalf("SIP008 was not parsed as Shadowsocks: %s", encoded)
	}
}

func TestParseProfileDocumentPortableConfigs(t *testing.T) {
	wireGuard := `[Interface]
Address = 10.0.0.2/32
PrivateKey = private
[Peer]
PublicKey = public
Endpoint = wg.example.com:51820`
	encoded, err := ParseProfileDocument(wireGuard)
	if err != nil {
		t.Fatal(err)
	}
	profiles := decodeProfiles(t, encoded)
	if len(profiles) != 1 || profiles[0]["kind"] != "wireguard" || profiles[0]["serverAddress"] != "wg.example.com" {
		t.Fatalf("unexpected wireguard: %s", encoded)
	}

	hysteria := `server: hy.example.com:443
auth: password
tls:
  sni: cdn.example.com
bandwidth:
  up: 20 mbps
  down: 100 mbps`
	encoded, err = ParseProfileDocument(hysteria)
	if err != nil {
		t.Fatal(err)
	}
	profiles = decodeProfiles(t, encoded)
	if len(profiles) != 1 || profiles[0]["protocolVersion"].(float64) != 2 || profiles[0]["sni"] != "cdn.example.com" {
		t.Fatalf("unexpected hysteria: %s", encoded)
	}
}

func TestNormalizeProfileSet(t *testing.T) {
	encoded, err := NormalizeProfileSet(`[
      {"kind":"socks","name":"Node","serverAddress":"a.example","serverPort":1},
      {"kind":"socks","name":"Node","serverAddress":"a.example","serverPort":1},
      {"kind":"socks","name":"Node (1)","serverAddress":"b.example","serverPort":2}
    ]`, true)
	if err != nil {
		t.Fatal(err)
	}
	var result normalizedProfileSet
	if err = json.Unmarshal([]byte(encoded), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Profiles) != 2 || result.Profiles[0]["name"] != "Node" || result.Profiles[1]["name"] != "Node (1) (1)" {
		t.Fatalf("unexpected profiles: %s", encoded)
	}
	if len(result.Duplicates) != 2 {
		t.Fatalf("unexpected duplicates: %s", encoded)
	}
}
