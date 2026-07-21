package libcore

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
)

const (
	maxProviderIdentityTypeBytes = 512
	maxProviderIdentityDataBytes = 8 * 1024 * 1024
)

// ProviderIdentityFingerprint creates the stable key used by subscription diff planning. Kotlin
// supplies the compatibility model's serialized ABI bytes; Go owns the pure hashing decision.
func ProviderIdentityFingerprint(typeName string, serialized []byte) (string, error) {
	if typeName == "" || len(typeName) > maxProviderIdentityTypeBytes {
		return "", errors.New("invalid provider identity type")
	}
	if len(serialized) == 0 || len(serialized) > maxProviderIdentityDataBytes {
		return "", errors.New("invalid provider identity data")
	}
	digest := sha256.New()
	_, _ = digest.Write([]byte(typeName))
	_, _ = digest.Write([]byte{0})
	_, _ = digest.Write(serialized)
	return hex.EncodeToString(digest.Sum(nil)), nil
}
