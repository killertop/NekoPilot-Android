package libcore

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

const (
	dnsRaceType        = "nekopilot-race"
	dnsRaceDelay       = 120 * time.Millisecond
	dnsRaceMaxChildren = 4
)

// racingDNSServerOptions is intentionally small. The child servers remain
// normal sing-box DNS transports, so their existing detours, TLS settings and
// caches are preserved. The wrapper only controls query scheduling.
type racingDNSServerOptions struct {
	Servers []string `json:"servers"`
	Delay   uint16   `json:"delay,omitempty"` // milliseconds before each fallback
}

type racingDNSTransport struct {
	dns.TransportAdapter
	manager adapter.DNSTransportManager
	servers []string
	delay   time.Duration
}

func newRacingDNSTransport(
	ctx context.Context,
	logger log.ContextLogger,
	tag string,
	options racingDNSServerOptions,
) (adapter.DNSTransport, error) {
	if len(options.Servers) < 2 {
		return nil, fmt.Errorf("%s requires at least two DNS servers", dnsRaceType)
	}
	if len(options.Servers) > dnsRaceMaxChildren {
		return nil, fmt.Errorf("%s supports at most %d DNS servers", dnsRaceType, dnsRaceMaxChildren)
	}
	manager := service.FromContext[adapter.DNSTransportManager](ctx)
	if manager == nil {
		return nil, errors.New("DNS transport manager is unavailable")
	}
	delay := dnsRaceDelay
	if options.Delay > 0 {
		delay = time.Duration(options.Delay) * time.Millisecond
	}
	servers := make([]string, 0, len(options.Servers))
	for _, server := range options.Servers {
		server = strings.TrimSpace(server)
		if server != "" {
			servers = append(servers, server)
		}
	}
	if len(servers) < 2 {
		return nil, fmt.Errorf("%s has fewer than two non-empty DNS servers", dnsRaceType)
	}
	return &racingDNSTransport{
		TransportAdapter: dns.NewTransportAdapter(dnsRaceType, tag, servers),
		manager:          manager,
		servers:          servers,
		delay:            delay,
	}, nil
}

func (r *racingDNSTransport) Start(adapter.StartStage) error {
	return nil
}

func (r *racingDNSTransport) Close() error {
	return nil
}

func (r *racingDNSTransport) Reset() {
	for _, tag := range r.servers {
		if child, loaded := r.manager.Transport(tag); loaded {
			child.Reset()
		}
	}
}

type dnsRaceResult struct {
	response *mDNS.Msg
	err      error
}

func (r *racingDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	results := make(chan dnsRaceResult, len(r.servers))
	for index, tag := range r.servers {
		go func(index int, tag string) {
			if index > 0 {
				timer := time.NewTimer(time.Duration(index) * r.delay)
				defer timer.Stop()
				select {
				case <-timer.C:
				case <-ctx.Done():
					results <- dnsRaceResult{err: ctx.Err()}
					return
				}
			}

			child, loaded := r.manager.Transport(tag)
			if !loaded {
				results <- dnsRaceResult{err: fmt.Errorf("DNS server %q not found", tag)}
				return
			}
			request := message.Copy()
			response, err := child.Exchange(ctx, request)
			if err == nil && response != nil {
				switch response.Rcode {
				case mDNS.RcodeSuccess, mDNS.RcodeNameError:
					results <- dnsRaceResult{response: response}
					return
				default:
					err = fmt.Errorf("DNS server %q returned rcode %d", tag, response.Rcode)
				}
			}
			if err == nil {
				err = fmt.Errorf("DNS server %q returned an empty response", tag)
			}
			results <- dnsRaceResult{err: err}
		}(index, tag)
	}

	errs := make([]error, 0, len(r.servers))
	for range r.servers {
		result := <-results
		if result.err == nil && result.response != nil {
			return result.response, nil
		}
		if result.err != nil && !errors.Is(result.err, context.Canceled) {
			errs = append(errs, result.err)
		}
	}
	if len(errs) == 0 {
		return nil, ctx.Err()
	}
	return nil, errors.Join(errs...)
}

// Keep the import contract explicit for future sing-box upgrades.
var _ adapter.LegacyDNSTransport = (*racingDNSTransport)(nil)
