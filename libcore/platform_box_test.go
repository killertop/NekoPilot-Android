package libcore

import "testing"

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
