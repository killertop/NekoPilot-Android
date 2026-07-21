package libcore

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestValidateRuleAssetRejectsUnsupportedAndMalformedFiles(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bad.db")
	if err := os.WriteFile(path, []byte("not a rule database"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ValidateRuleAsset("unsupported.db", path); err == nil {
		t.Fatal("expected unsupported asset rejection")
	}
	if err := ValidateRuleAsset(geoipDat, path); err == nil {
		t.Fatal("expected malformed geoip rejection")
	}
	if err := ValidateRuleAsset(geositeDat, path); err == nil {
		t.Fatal("expected malformed geosite rejection")
	}
}

func TestParseRuleAssetChecksum(t *testing.T) {
	digest := strings.Repeat("A", 64)
	parsed, err := ParseRuleAssetChecksum(geoipDat, []byte(digest+"  geoip.db\n"))
	if err != nil || parsed != strings.ToLower(digest) {
		t.Fatalf("unexpected checksum result: %q, %v", parsed, err)
	}
	for _, invalid := range [][]byte{
		nil,
		[]byte("not-a-checksum"),
		[]byte(strings.Repeat("a", 63)),
		[]byte(strings.Repeat("a", maxRuleAssetChecksumBytes+1)),
	} {
		if _, err := ParseRuleAssetChecksum(geoipDat, invalid); err == nil {
			t.Fatalf("invalid checksum was accepted: %q", invalid)
		}
	}
}

func TestVerifyRuleAssetRejectsChecksumMismatchBeforeParsing(t *testing.T) {
	path := filepath.Join(t.TempDir(), "asset")
	content := []byte("not a database")
	if err := os.WriteFile(path, content, 0o600); err != nil {
		t.Fatal(err)
	}
	wrong := strings.Repeat("0", sha256.Size*2)
	if err := VerifyRuleAsset(geoipDat, path, []byte(wrong)); err == nil ||
		!strings.Contains(err.Error(), "checksum verification failed") {
		t.Fatalf("checksum mismatch was not reported first: %v", err)
	}
	digest := sha256.Sum256(content)
	matching := hex.EncodeToString(digest[:])
	if err := VerifyRuleAsset(geoipDat, path, []byte(matching)); err == nil ||
		strings.Contains(err.Error(), "checksum verification failed") {
		t.Fatalf("matching checksum did not reach database validation: %v", err)
	}
}
