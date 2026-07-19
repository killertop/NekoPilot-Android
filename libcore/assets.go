package libcore

import "fmt"

const (
	geoipDat       = "geoip.db"
	geositeDat     = "geosite.db"
	geoipVersion   = "geoip.version.txt"
	geositeVersion = "geosite.version.txt"
)

var apkAssetPrefixSingBox = "sing-box/"
var internalAssetsPath string
var externalAssetsPath string

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
