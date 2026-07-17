package libcore

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
)

func decodeProfiles(t *testing.T, encoded string) []map[string]any {
	t.Helper()
	var profiles []map[string]any
	if err := json.Unmarshal([]byte(encoded), &profiles); err != nil {
		t.Fatal(err)
	}
	return profiles
}

func TestParseProfileLinksCommonFormats(t *testing.T) {
	vmessJSON := `{"v":"2","ps":"vm","add":"example.com","port":"443","id":"id-1","aid":"0","net":"ws","host":"cdn.example.com","path":"/ws","tls":"tls","sni":"sni.example.com"}`
	vmess := "vmess://" + base64.RawStdEncoding.EncodeToString([]byte(vmessJSON))
	input := vmess + "\n" +
		"vless://uuid@example.com:8443?type=grpc&security=reality&sni=server.example&pbk=pub&sid=01#vl\n" +
		"trojan://secret@example.net:443?type=ws&host=cdn.example.net&path=%2Ftr#tr\n" +
		"ss://" + base64.RawURLEncoding.EncodeToString([]byte("aes-256-gcm:pass")) + "@1.2.3.4:8388#ss\n" +
		"tuic://uuid:password@tuic.example:443?sni=tuic.example&congestion_control=bbr#tuic\n" +
		"anytls://password@any.example:443?sni=any.example&fp=chrome#any"
	encoded, err := ParseProfileLinks(input)
	if err != nil {
		t.Fatal(err)
	}
	profiles := decodeProfiles(t, encoded)
	if len(profiles) != 6 {
		t.Fatalf("got %d profiles: %s", len(profiles), encoded)
	}
	if profiles[0]["kind"] != "vmess" || profiles[0]["serverAddress"] != "example.com" {
		t.Fatalf("unexpected VMess: %#v", profiles[0])
	}
	if profiles[1]["realityPubKey"] != "pub" || profiles[1]["alterId"] != float64(-1) {
		t.Fatalf("unexpected VLESS: %#v", profiles[1])
	}
	if profiles[3]["method"] != "aes-256-gcm" || profiles[3]["password"] != "pass" {
		t.Fatalf("unexpected Shadowsocks: %#v", profiles[3])
	}
}

func TestParseProfileLinksSkipsMalformedAndBoundsInput(t *testing.T) {
	encoded, err := ParseProfileLinks("not-a-link\nsocks5://user:pass@127.0.0.1:1080")
	if err != nil {
		t.Fatal(err)
	}
	profiles := decodeProfiles(t, encoded)
	if len(profiles) != 1 || profiles[0]["kind"] != "socks" {
		t.Fatalf("unexpected profiles: %s", encoded)
	}
	tooLarge := make([]byte, maxPortableConfigBytes+1)
	if _, err = ParseProfileLinks(string(tooLarge)); err == nil {
		t.Fatal("expected size limit error")
	}
}

func TestEncodeProfileLinkRoundTripsVLESSAndShadowsocks(t *testing.T) {
	vless, err := EncodeProfileLink("vless", `{"serverAddress":"example.com","serverPort":443,"name":"node","uuid":"id","type":"grpc","path":"svc","security":"tls","sni":"sni.example","realityPubKey":"pub","realityShortId":"01"}`)
	if err != nil {
		t.Fatal(err)
	}
	profile, err := parseProfileLink(vless)
	if err != nil || profile["uuid"] != "id" || profile["path"] != "svc" || profile["name"] != "node" || profile["realityPubKey"] != "pub" {
		t.Fatalf("unexpected VLESS round trip: %s %#v %v", vless, profile, err)
	}
	if !strings.Contains(vless, "security=reality") {
		t.Fatalf("VLESS Reality security marker lost: %s", vless)
	}
	ss, err := EncodeProfileLink("ss", `{"serverAddress":"1.2.3.4","serverPort":8388,"name":"ss","method":"aes-256-gcm","password":"secret","plugin":"v2ray-plugin;mode=websocket"}`)
	if err != nil {
		t.Fatal(err)
	}
	profile, err = parseProfileLink(ss)
	if err != nil || profile["method"] != "aes-256-gcm" || profile["password"] != "secret" || profile["plugin"] != "v2ray-plugin;mode=websocket" {
		t.Fatalf("unexpected SS round trip: %s %#v %v", ss, profile, err)
	}
}

func TestProfileLinkCompatibilityFieldsRoundTrip(t *testing.T) {
	tests := []struct {
		kind    string
		profile string
		check   func(map[string]any) bool
	}{
		{
			kind:    "trojan-go",
			profile: `{"serverAddress":"tr.example","serverPort":443,"password":"secret","type":"ws","host":"cdn.example","path":"/ws","encryption":"ss;aes-128-gcm:pass","plugin":"plugin-id"}`,
			check: func(profile map[string]any) bool {
				return profile["encryption"] == "ss;aes-128-gcm:pass" && profile["plugin"] == "plugin-id"
			},
		},
		{
			kind:    "tuic",
			profile: `{"serverAddress":"tuic.example","serverPort":443,"uuid":"id","token":"secret","disableSNI":true,"reduceRTT":true}`,
			check: func(profile map[string]any) bool {
				return profile["disableSNI"] == true && profile["reduceRTT"] == true
			},
		},
		{
			kind:    "hysteria",
			profile: `{"serverAddress":"hy.example","serverPort":443,"serverPorts":"443","protocolVersion":1,"authPayload":"secret","sni":"sni.example","alpn":"hysteria","obfuscation":"obfs-secret","protocol":1}`,
			check: func(profile map[string]any) bool {
				return profile["alpn"] == "hysteria" && profile["obfuscation"] == "obfs-secret" && profile["protocol"] == float64(1)
			},
		},
	}
	for _, test := range tests {
		t.Run(test.kind, func(t *testing.T) {
			link, err := EncodeProfileLink(test.kind, test.profile)
			if err != nil {
				t.Fatal(err)
			}
			profile, err := parseProfileLink(link)
			if err != nil || !test.check(profile) {
				t.Fatalf("unexpected %s round trip: %s %#v %v", test.kind, link, profile, err)
			}
		})
	}
}

func TestSocksDefaultVersionV2RayNCredentialsAndNaiveQuic(t *testing.T) {
	credentials := base64.RawURLEncoding.EncodeToString([]byte("user:pass"))
	profiles := []string{
		"socks://127.0.0.1:1080",
		"socks://" + credentials + "@127.0.0.1:1080",
		"naive+quic://user:pass@naive.example:443?sni=edge.example&insecure-concurrency=4",
	}
	for index, link := range profiles {
		profile, err := parseProfileLink(link)
		if err != nil {
			t.Fatalf("case %d: %v", index, err)
		}
		switch index {
		case 0:
			if profile["protocol"] != 2 {
				t.Fatalf("SOCKS default is not v5: %#v", profile)
			}
		case 1:
			if profile["username"] != "user" || profile["password"] != "pass" {
				t.Fatalf("v2rayN credentials lost: %#v", profile)
			}
		case 2:
			if profile["proto"] != "quic" || profile["sni"] != "edge.example" || profile["insecureConcurrency"] != 4 {
				t.Fatalf("Naive QUIC fields lost: %#v", profile)
			}
		}
	}
}

func TestHTTPSubscriptionURLIsNotParsedAsProxy(t *testing.T) {
	encoded, err := ParseProfileLinks("https://example.com/subscription/path")
	if err != nil {
		t.Fatal(err)
	}
	if encoded != "[]" {
		t.Fatalf("unexpected HTTP profile: %s", encoded)
	}
}
