package libcore

import (
	"context"
	"sync"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/option"
)

type testDNSFunc func() error

func (f testDNSFunc) Invoke() error {
	return f()
}

type testLocalDNSTransport struct{}

func (testLocalDNSTransport) Raw() bool {
	return false
}

func (testLocalDNSTransport) NetworkHandle() int64 {
	return 0
}

func (testLocalDNSTransport) Lookup(*ExchangeContext, string, string) error {
	return nil
}

func (testLocalDNSTransport) Exchange(*ExchangeContext, []byte) error {
	return nil
}

func TestExchangeContextSuccessCompletesAndSuppressesLateCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	completed := make(chan struct{})
	resolver := &ExchangeContext{
		context:  ctx,
		complete: completed,
		done: sync.OnceFunc(func() {
			close(completed)
		}),
	}
	cancelled := make(chan struct{}, 1)
	resolver.OnCancel(testDNSFunc(func() error {
		cancelled <- struct{}{}
		return nil
	}))

	resolver.Success("1.1.1.1\n2606:4700:4700::1111")
	select {
	case <-completed:
	case <-time.After(time.Second):
		t.Fatal("successful lookup did not complete")
	}
	if len(resolver.addresses) != 2 {
		t.Fatalf("unexpected addresses: %#v", resolver.addresses)
	}

	cancel()
	select {
	case <-cancelled:
		t.Fatal("completed lookup invoked a late cancellation callback")
	case <-time.After(50 * time.Millisecond):
	}
}

func TestPlatformLocalDNSTransportRejectsRequestWithoutQuestion(t *testing.T) {
	transport := newPlatformTransport(testLocalDNSTransport{}, "", option.LocalDNSServerOptions{})
	if _, err := transport.Exchange(context.Background(), new(mDNS.Msg)); err == nil {
		t.Fatal("expected an empty DNS request to be rejected")
	}
}
