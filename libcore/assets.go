package libcore

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"

	"github.com/sagernet/sing-box/common/srs"
)

const (
	geoipRuleSet          = "geoip-cn.srs"
	geositeRuleSet        = "geosite-cn.srs"
	geoipRuleSetVersion   = "geoip-cn.version.txt"
	geositeRuleSetVersion = "geosite-cn.version.txt"
)

var apkAssetPrefixSingBox = "sing-box/"
var internalAssetsPath string
var externalAssetsPath string

// RuleAssetDigest validates a standard sing-box SRS rule-set and returns its
// SHA-256. Kotlin uses the digest as the installed version after atomically
// downloading an asset from an official source.
func RuleAssetDigest(name, path string) (string, error) {
	if err := ValidateRuleAsset(name, path); err != nil {
		return "", err
	}
	file, err := os.Open(path)
	if err != nil {
		return "", fmt.Errorf("open %s: %w", name, err)
	}
	digest := sha256.New()
	_, copyErr := io.Copy(digest, file)
	closeErr := file.Close()
	if copyErr != nil {
		return "", fmt.Errorf("hash %s: %w", name, copyErr)
	}
	if closeErr != nil {
		return "", fmt.Errorf("close %s: %w", name, closeErr)
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

// ValidateRuleAsset accepts only the two bundled standard sing-box binary rule
// sets and checks that each one contains at least one valid rule.
func ValidateRuleAsset(name string, path string) error {
	switch name {
	case geoipRuleSet, geositeRuleSet:
		file, err := os.Open(path)
		if err != nil {
			return fmt.Errorf("open %s: %w", name, err)
		}
		defer file.Close()
		compat, err := srs.Read(file, false)
		if err != nil {
			return fmt.Errorf("read %s: %w", name, err)
		}
		ruleSet, err := compat.Upgrade()
		if err != nil {
			return fmt.Errorf("upgrade %s: %w", name, err)
		}
		if len(ruleSet.Rules) == 0 {
			return fmt.Errorf("%s has no rules", name)
		}
		return nil
	default:
		return fmt.Errorf("unsupported rule asset: %s", name)
	}
}
