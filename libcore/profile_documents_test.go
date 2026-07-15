package libcore

import (
	"encoding/base64"
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
