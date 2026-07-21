package libcore

import "testing"

func TestProviderIdentityFingerprintIsTypedAndDeterministic(t *testing.T) {
	first, err := ProviderIdentityFingerprint("example.Bean", []byte{1, 2, 3})
	if err != nil {
		t.Fatal(err)
	}
	again, err := ProviderIdentityFingerprint("example.Bean", []byte{1, 2, 3})
	if err != nil || again != first {
		t.Fatalf("fingerprint is not deterministic: %q, %q, %v", first, again, err)
	}
	otherType, err := ProviderIdentityFingerprint("example.OtherBean", []byte{1, 2, 3})
	if err != nil || otherType == first {
		t.Fatal("model type was not included in the fingerprint")
	}
	if _, err := ProviderIdentityFingerprint("", []byte{1}); err == nil {
		t.Fatal("empty type was accepted")
	}
	if _, err := ProviderIdentityFingerprint("example.Bean", nil); err == nil {
		t.Fatal("empty serialized identity was accepted")
	}
}
