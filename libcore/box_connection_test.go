package libcore

import (
	"context"
	"net"
	"sync/atomic"
	"testing"

	box "github.com/sagernet/sing-box"
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

func TestAcquireMainInstanceRequiresRunningCurrentBox(t *testing.T) {
	previous := mainInstance.Swap(nil)
	defer mainInstance.Store(previous)

	instance := &BoxInstance{Box: new(box.Box), state: 1}
	mainInstance.Store(instance)
	acquired, release := acquireMainInstance()
	if acquired != instance || release == nil {
		t.Fatal("running main instance was not acquired")
	}
	release()

	instance.access.Lock()
	instance.state = 2
	instance.access.Unlock()
	if acquired, release = acquireMainInstance(); acquired != nil || release != nil {
		t.Fatal("closed main instance must not be acquired")
	}
}

func TestAcquireBoxInstanceRequiresRunningBox(t *testing.T) {
	instance := &BoxInstance{Box: new(box.Box), state: 1}
	acquired, release := acquireBoxInstance(instance)
	if acquired != instance || release == nil {
		t.Fatal("running box instance was not acquired")
	}
	release()

	instance.access.Lock()
	instance.state = 0
	instance.access.Unlock()
	if acquired, release = acquireBoxInstance(instance); acquired != nil || release != nil {
		t.Fatal("stopped box instance must not be acquired")
	}
}

func TestFailedStartDoesNotMarkInstanceRunning(t *testing.T) {
	instance := new(BoxInstance)
	if err := instance.Start(); err == nil {
		t.Fatal("starting without a box must fail")
	}
	if instance.state != 0 {
		t.Fatalf("failed start left invalid state %d", instance.state)
	}
}
