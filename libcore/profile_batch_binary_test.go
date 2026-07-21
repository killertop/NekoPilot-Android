package libcore

import (
	"bytes"
	"testing"
)

func TestProfileBatchBinaryRoundTripAndBounds(t *testing.T) {
	encoded, err := ParseProfileLinksBinary("socks5://user:pass@127.0.0.1:1080#Local")
	if err != nil {
		t.Fatal(err)
	}
	batch, err := decodeProfileBatch(encoded, profileBatchTypeProfiles)
	if err != nil {
		t.Fatal(err)
	}
	if len(batch.profiles) != 1 || anyString(batch.profiles[0]["kind"]) != "socks" ||
		anyString(batch.profiles[0]["name"]) != "Local" {
		t.Fatalf("unexpected binary profile batch: %#v", batch)
	}
	if _, err = decodeProfileBatch(append(encoded, 0), profileBatchTypeProfiles); err == nil {
		t.Fatal("trailing binary profile data was accepted")
	}
	if _, err = decodeProfileBatch(bytes.Repeat([]byte{1}, 16), profileBatchTypeProfiles); err == nil {
		t.Fatal("invalid binary profile header was accepted")
	}
}

func TestNormalizeProfileSetBinary(t *testing.T) {
	input, err := ParseProfileLinksBinary("socks5://127.0.0.1:1080#A\nsocks5://127.0.0.1:1080#B")
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := NormalizeProfileSetBinary(input, true)
	if err != nil {
		t.Fatal(err)
	}
	batch, err := decodeProfileBatch(encoded, profileBatchTypeNormalize)
	if err != nil {
		t.Fatal(err)
	}
	if len(batch.profiles) != 1 || len(batch.metadata) == 0 {
		t.Fatalf("binary normalization lost result metadata: %#v", batch)
	}
}

func TestProfileDocumentBinaryKeepsParsedFields(t *testing.T) {
	input := `proxies:
  - {name: edge, type: vless, server: example.com, port: 443, uuid: test-id, tls: true}`
	encoded, err := ParseProfileDocumentBinary(input)
	if err != nil {
		t.Fatal(err)
	}
	batch, err := decodeProfileBatch(encoded, profileBatchTypeProfiles)
	if err != nil {
		t.Fatal(err)
	}
	if len(batch.profiles) != 1 || anyString(batch.profiles[0]["name"]) != "edge" ||
		anyString(batch.profiles[0]["serverAddress"]) != "example.com" {
		t.Fatalf("binary document parse lost profile fields: %#v", batch)
	}
}

func TestSubscriptionDocumentBinaryKeepsPartialParseMetadata(t *testing.T) {
	input := `proxies:
  - {name: socks, type: socks5, server: 127.0.0.1, port: 1080}
  - {name: unsupported, type: snell, server: invalid.example, port: 443}
  - malformed-entry`
	encoded, err := ParseSubscriptionDocumentBinary(input)
	if err != nil {
		t.Fatal(err)
	}
	batch, err := decodeProfileBatch(encoded, profileBatchTypeSubscribe)
	if err != nil {
		t.Fatal(err)
	}
	if len(batch.profiles) != 1 || len(batch.metadata) != 1 ||
		batch.metadata[0] != "unsupported" || !batch.hasUnnamedSkipped {
		t.Fatalf("binary subscription parse lost recovery metadata: %#v", batch)
	}
}
