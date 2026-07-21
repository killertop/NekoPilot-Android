package libcore

import (
	"fmt"
	"syscall"
	"testing"
)

type wifiStateTestInterface struct {
	BoxPlatformInterface
	state string
}

func (w wifiStateTestInterface) WIFIState() string { return w.state }

func TestReadWIFIStatePreservesCommaInSSID(t *testing.T) {
	previous := intfBox
	defer func() { intfBox = previous }()
	intfBox = wifiStateTestInterface{state: "office,guest,aa:bb:cc:dd:ee:ff"}
	state := boxPlatformInterfaceInstance.ReadWIFIState()
	if state.SSID != "office,guest" || state.BSSID != "aa:bb:cc:dd:ee:ff" {
		t.Fatalf("unexpected Wi-Fi state: %#v", state)
	}
}

func TestReadWIFIStateRejectsMalformedValue(t *testing.T) {
	previous := intfBox
	defer func() { intfBox = previous }()
	intfBox = wifiStateTestInterface{state: "missing separator"}
	state := boxPlatformInterfaceInstance.ReadWIFIState()
	if state.SSID != "" || state.BSSID != "" {
		t.Fatalf("malformed Wi-Fi state was accepted: %#v", state)
	}
}

func TestProtectServerUnavailable(t *testing.T) {
	for _, err := range []error{
		syscall.ENOENT,
		fmt.Errorf("wrapped: %w", syscall.ECONNREFUSED),
	} {
		if !protectServerUnavailable(err) {
			t.Fatalf("expected unavailable protect server for %v", err)
		}
	}
	if protectServerUnavailable(syscall.EACCES) {
		t.Fatal("permission failures must not bypass socket protection")
	}
}
