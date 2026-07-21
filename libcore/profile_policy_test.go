package libcore

import "testing"

func TestHysteriaServerHasMultiplePorts(t *testing.T) {
	tests := []struct {
		value string
		want  bool
	}{
		{"example.com:443", false},
		{"example.com:443,8443", true},
		{"example.com:443-445", true},
		{"[2001:db8::1]:443", false},
		{"[2001:db8::1]:443,8443", true},
		{"2001:db8::1", false},
		{"example.com", false},
		{"example.com:", false},
	}
	for _, test := range tests {
		if got := HysteriaServerHasMultiplePorts(test.value); got != test.want {
			t.Errorf("HysteriaServerHasMultiplePorts(%q) = %v, want %v", test.value, got, test.want)
		}
	}
}

func TestHysteriaNeedsExternal(t *testing.T) {
	if HysteriaNeedsExternal(0) {
		t.Fatal("UDP transport must stay in sing-box")
	}
	if !HysteriaNeedsExternal(1) || !HysteriaNeedsExternal(2) {
		t.Fatal("legacy non-UDP transports must use the external compatibility process")
	}
}

func TestClassifyConnectionFailure(t *testing.T) {
	tests := []struct {
		message string
		want    int32
	}{
		{"context deadline exceeded", connectionFailureTimeout},
		{"i/o timeout", connectionFailureTimeout},
		{"use of closed network connection", connectionFailureReset},
		{"read: connection reset by peer", connectionFailureReset},
		{"EOF", connectionFailureReset},
		{"certificate is not trusted", connectionFailureOther},
	}
	for _, test := range tests {
		if got := ClassifyConnectionFailure(test.message); got != test.want {
			t.Errorf("ClassifyConnectionFailure(%q) = %d, want %d", test.message, got, test.want)
		}
	}
}
