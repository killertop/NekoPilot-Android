package libcore

import (
	"encoding/json"
	"testing"
)

func buildOutboundForTest(t *testing.T, kind, profile string) map[string]any {
	t.Helper()
	encoded, err := BuildProfileOutbound(kind, profile, false)
	if err != nil {
		t.Fatal(err)
	}
	var outbound map[string]any
	if err = json.Unmarshal([]byte(encoded), &outbound); err != nil {
		t.Fatal(err)
	}
	if err = ValidateSingBoxConfig(`{"outbounds":[` + encoded + `]}`); err != nil {
		t.Fatalf("sing-box rejected %s outbound: %v\n%s", kind, err, encoded)
	}
	return outbound
}

func TestBuildProfileOutboundVLESSRealityWebSocket(t *testing.T) {
	outbound := buildOutboundForTest(t, "vless", `{
      "serverAddress":"example.com","serverPort":443,"uuid":"id","encryption":"xtls-rprx-vision",
      "security":"tls","sni":"sni.example.com","realityPubKey":"pub","realityShortId":"01",
      "type":"ws","host":"cdn.example.com","path":"/ws","wsMaxEarlyData":2048
    }`)
	if outbound["type"] != "vless" || outbound["flow"] != "xtls-rprx-vision" {
		t.Fatalf("unexpected outbound: %#v", outbound)
	}
	tls := outbound["tls"].(map[string]any)
	if tls["server_name"] != "sni.example.com" || tls["reality"].(map[string]any)["public_key"] != "pub" {
		t.Fatalf("unexpected TLS: %#v", tls)
	}
	transport := outbound["transport"].(map[string]any)
	if transport["type"] != "ws" || transport["max_early_data"] != float64(2048) {
		t.Fatalf("unexpected transport: %#v", transport)
	}
}

func TestBuildProfileOutboundHysteriaAndAnyTLS(t *testing.T) {
	hy2 := buildOutboundForTest(t, "hysteria2", `{
      "serverAddress":"hy.example","serverPorts":"443,8443-8450","hopInterval":10,
      "authPayload":"secret","obfuscation":"obfs","sni":"hy.example","allowInsecure":false
    }`)
	if hy2["type"] != "hysteria2" || len(hy2["server_ports"].([]any)) != 2 {
		t.Fatalf("unexpected Hysteria2: %#v", hy2)
	}
	anyTLS := buildOutboundForTest(t, "anytls", `{
      "serverAddress":"any.example","serverPort":443,"password":"secret","sni":"any.example",
      "utlsFingerprint":"chrome","echConfig":"config-line"
    }`)
	tls := anyTLS["tls"].(map[string]any)
	if anyTLS["type"] != "anytls" || tls["utls"].(map[string]any)["fingerprint"] != "chrome" {
		t.Fatalf("unexpected AnyTLS: %#v", anyTLS)
	}
}

func TestBuildProfileOutboundPreservesSocks4A(t *testing.T) {
	socks := buildOutboundForTest(t, "socks", `{"serverAddress":"127.0.0.1","serverPort":1080,"protocol":1}`)
	if socks["version"] != "4a" {
		t.Fatalf("unexpected SOCKS version: %#v", socks)
	}
}

func TestBuildProfileOutboundAllSupportedKindsDecodeWithSingBox(t *testing.T) {
	cases := map[string]string{
		"http":      `{"serverAddress":"proxy.example","serverPort":443,"username":"u","password":"p","security":"tls","sni":"proxy.example"}`,
		"vmess":     `{"serverAddress":"vm.example","serverPort":443,"uuid":"00000000-0000-0000-0000-000000000000","alterId":0,"encryption":"auto","type":"ws","path":"/ws"}`,
		"trojan":    `{"serverAddress":"tr.example","serverPort":443,"password":"p","security":"tls","sni":"tr.example","type":"tcp"}`,
		"shadowtls": `{"serverAddress":"st.example","serverPort":443,"version":3,"password":"p","security":"tls","sni":"st.example"}`,
		"hysteria":  `{"serverAddress":"hy.example","serverPorts":"443","protocolVersion":1,"authPayloadType":1,"authPayload":"p","sni":"hy.example","uploadMbps":10,"downloadMbps":50}`,
		"tuic":      `{"serverAddress":"tuic.example","serverPort":443,"protocolVersion":5,"uuid":"00000000-0000-0000-0000-000000000000","token":"p","sni":"tuic.example","congestionController":"cubic"}`,
		"ss":        `{"serverAddress":"1.2.3.4","serverPort":8388,"method":"aes-256-gcm","password":"p"}`,
		"ssh":       `{"serverAddress":"ssh.example","serverPort":22,"username":"root","authType":1,"password":"p"}`,
	}
	for kind, profile := range cases {
		t.Run(kind, func(t *testing.T) { buildOutboundForTest(t, kind, profile) })
	}
}
