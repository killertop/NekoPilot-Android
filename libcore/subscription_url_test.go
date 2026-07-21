package libcore

import (
	"strings"
	"testing"
)

func TestCanonicalSubscriptionURL(t *testing.T) {
	equalPairs := [][2]string{
		{"https://host.example/path", "HTTPS://HOST.EXAMPLE:443/path#temporary"},
		{"https://host.example/path", "https://host.example:0443/path"},
		{"http://host.example", "HTTP://HOST.EXAMPLE:80/"},
		{"http://host.example", "http://host.example:00080/"},
		{"https://host.example/path", "https://host.example.../path"},
		{"https://例子.测试/sub?token=signed", "https://xn--fsqu00a.xn--0zwm56d/sub?token=signed"},
		{"HTTPS://[2001:DB8::1]:443/sub?token=A%2Fb#ignored", "https://[2001:db8::1]/sub?token=A%2Fb"},
	}
	for _, pair := range equalPairs {
		first := CanonicalSubscriptionURL(pair[0])
		second := CanonicalSubscriptionURL(pair[1])
		if first == "" || first != second {
			t.Fatalf("canonical mismatch: %q != %q", first, second)
		}
	}
	if CanonicalSubscriptionURL("file:///tmp/subscription") != "" ||
		CanonicalSubscriptionURL("not a url") != "" {
		t.Fatal("invalid subscription URL was accepted")
	}
}

func TestSameSubscriptionURLPreservesProviderIdentity(t *testing.T) {
	differentPairs := [][2]string{
		{"https://host.example/a?token=A%2Fb", "https://host.example/a?token=A/b"},
		{"https://alice@host.example/a", "https://bob@host.example/a"},
		{"https://例子.测试/sub", "https://例子.中国/sub"},
		{"first invalid url", "second invalid url"},
		{"https://host.example/path?", "https://host.example/path"},
	}
	for _, pair := range differentPairs {
		if SameSubscriptionURL(pair[0], pair[1]) {
			t.Fatalf("distinct subscriptions collided: %q and %q", pair[0], pair[1])
		}
	}
	if !SameSubscriptionURL(" same invalid url ", "same invalid url") {
		t.Fatal("trimmed fallback identity changed")
	}
}

func TestSubscriptionURLBoundsAreEnforcedAtExportedBoundary(t *testing.T) {
	oversized := "https://host.example/" + strings.Repeat("a", maxSubscriptionURLUTF16Units)
	if CanonicalSubscriptionURL(oversized) != "" {
		t.Fatal("oversized subscription URL was accepted")
	}
	if SameSubscriptionURL(oversized, oversized) {
		t.Fatal("two rejected oversized URLs compared equal")
	}
}
