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
