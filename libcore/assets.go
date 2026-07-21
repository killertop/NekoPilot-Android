package libcore

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"strings"
)

const (
	geoipDat       = "geoip.db"
	geositeDat     = "geosite.db"
	geoipVersion   = "geoip.version.txt"
	geositeVersion = "geosite.version.txt"
)

var apkAssetPrefixSingBox = "sing-box/"
var internalAssetsPath string
var externalAssetsPath string

const maxRuleAssetChecksumBytes = 512

// ParseRuleAssetChecksum validates the checksum sidecar format and returns canonical lowercase
// SHA-256. Keeping this parser in Go prevents download-source differences from changing the
// integrity decision made by the native rule reader.
func ParseRuleAssetChecksum(fileName string, checksum []byte) (string, error) {
	if len(checksum) == 0 || len(checksum) > maxRuleAssetChecksumBytes {
		return "", fmt.Errorf("%s has an invalid checksum", fileName)
	}
	fields := bytes.Fields(checksum)
	if len(fields) == 0 {
		return "", fmt.Errorf("%s has an invalid checksum", fileName)
	}
	expected := strings.ToLower(string(fields[0]))
	decoded, err := hex.DecodeString(expected)
	if err != nil || len(decoded) != sha256.Size {
		return "", fmt.Errorf("%s has an invalid checksum", fileName)
	}
	return expected, nil
}

// VerifyRuleAsset performs checksum and database-content validation as one native integrity
// boundary before Kotlin atomically installs a downloaded rule asset.
func VerifyRuleAsset(name, path string, checksum []byte) error {
	expected, err := ParseRuleAssetChecksum(name, checksum)
	if err != nil {
		return err
	}
	file, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open %s: %w", name, err)
	}
	digest := sha256.New()
	_, copyErr := io.Copy(digest, file)
	closeErr := file.Close()
	if copyErr != nil {
		return fmt.Errorf("hash %s: %w", name, copyErr)
	}
	if closeErr != nil {
		return fmt.Errorf("close %s: %w", name, closeErr)
	}
	actual := hex.EncodeToString(digest.Sum(nil))
	if actual != expected {
		return fmt.Errorf("%s checksum verification failed", name)
	}
	return ValidateRuleAsset(name, path)
}

// ValidateRuleAsset checks the exact data needed by NekoPilot's built-in China
// direct rules before a downloaded database replaces the previous working copy.
func ValidateRuleAsset(name string, path string) error {
	switch name {
	case geoipDat:
		reader := new(geoip)
		if err := reader.Open(path); err != nil {
			return err
		}
		defer reader.geoipReader.Close()
		_, err := reader.Rules("cn")
		return err
	case geositeDat:
		reader := new(geosite)
		if err := reader.Open(path); err != nil {
			return err
		}
		defer reader.Close()
		_, err := reader.Rules("cn")
		return err
	default:
		return fmt.Errorf("unsupported rule asset: %s", name)
	}
}
