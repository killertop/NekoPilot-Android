package libcore

import (
	"context"
	"net"
	"sync/atomic"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	B "github.com/sagernet/sing/common/bufio"
)

func TestConnectionCounterTracksTCPUntilClose(t *testing.T) {
	var tcp, udp atomic.Int64
	counter := connectionCounter{activeTCP: &tcp, activeUDP: &udp}
	left, right := net.Pipe()
	defer right.Close()

	tracked := counter.RoutedConnection(
		context.Background(), left, adapter.InboundContext{}, nil, nil,
	)
	if tcp.Load() != 1 || udp.Load() != 0 {
		t.Fatalf("unexpected counters: tcp=%d udp=%d", tcp.Load(), udp.Load())
	}
	if err := tracked.Close(); err != nil {
		t.Fatal(err)
	}
	_ = tracked.Close()
	if tcp.Load() != 0 {
		t.Fatalf("TCP counter was not released exactly once: %d", tcp.Load())
	}
}

func TestConnectionCounterTracksUDPAndPreservesUnwrapping(t *testing.T) {
	var tcp, udp atomic.Int64
	counter := connectionCounter{activeTCP: &tcp, activeUDP: &udp}
	packetConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}

	tracked := counter.RoutedPacketConnection(
		context.Background(), B.NewPacketConn(packetConn), adapter.InboundContext{}, nil, nil,
	)
	if tcp.Load() != 0 || udp.Load() != 1 {
		t.Fatalf("unexpected counters: tcp=%d udp=%d", tcp.Load(), udp.Load())
	}
	if upstream, ok := tracked.(interface{ Upstream() any }); !ok || upstream.Upstream() == nil {
		t.Fatal("tracked packet connection cannot be unwrapped")
	}
	if err = tracked.Close(); err != nil {
		t.Fatal(err)
	}
	_ = tracked.Close()
	if udp.Load() != 0 {
		t.Fatalf("UDP counter was not released exactly once: %d", udp.Load())
	}
}
