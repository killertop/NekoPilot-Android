package libcore

import (
	"os"
	"path/filepath"
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
