package libcore

import (
	"os"
	"path/filepath"
	"testing"
)

func TestValidateRuleAssetRejectsUnsupportedAndMalformedFiles(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bad.srs")
	if err := os.WriteFile(path, []byte("not a rule set"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ValidateRuleAsset("unsupported.db", path); err == nil {
		t.Fatal("expected unsupported asset rejection")
	}
	if err := ValidateRuleAsset(geoipRuleSet, path); err == nil {
		t.Fatal("expected malformed geoip rejection")
	}
	if err := ValidateRuleAsset(geositeRuleSet, path); err == nil {
		t.Fatal("expected malformed geosite rejection")
	}
}

func TestRuleAssetDigestRejectsMalformedRuleSet(t *testing.T) {
	path := filepath.Join(t.TempDir(), "asset")
	if err := os.WriteFile(path, []byte("not a rule set"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := RuleAssetDigest(geoipRuleSet, path); err == nil {
		t.Fatal("expected malformed rule-set rejection")
	}
}
