package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"net"
	"net/http"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	dialer.DoNotSelectInterface = true
}

var mainInstance *BoxInstance

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if mainInstance == nil || mainInstance.Box == nil {
		return
	}
	mainInstance.Box.Router().ResetNetwork()
	log.Printf("Reset tracked connections and network state done (system=%t)", system)
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  int

	selector     *group.Selector
	pauseManager pause.Manager
	activeTCP    atomic.Int64
	activeUDP    atomic.Int64
}

type connectionCounter struct {
	activeTCP *atomic.Int64
	activeUDP *atomic.Int64
}
type countedConnection struct {
	net.Conn
	active *atomic.Int64
	once   sync.Once
}
type countedPacketConnection struct {
	N.PacketConn
	active *atomic.Int64
	once   sync.Once
}

func (t *connectionCounter) RoutedConnection(_ context.Context, conn net.Conn, _ adapter.InboundContext, _ adapter.Rule, _ adapter.Outbound) net.Conn {
	t.activeTCP.Add(1)
	return &countedConnection{Conn: conn, active: t.activeTCP}
}

func (t *connectionCounter) RoutedPacketConnection(_ context.Context, conn N.PacketConn, _ adapter.InboundContext, _ adapter.Rule, _ adapter.Outbound) N.PacketConn {
	t.activeUDP.Add(1)
	return &countedPacketConnection{PacketConn: conn, active: t.activeUDP}
}

func (c *countedConnection) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() { c.active.Add(-1) })
	return err
}

func (c *countedPacketConnection) Close() error {
	err := c.PacketConn.Close()
	c.once.Do(func() { c.active.Add(-1) })
	return err
}

func (c *countedPacketConnection) Upstream() any {
	return c.PacketConn
}

func (c *countedPacketConnection) ReaderReplaceable() bool {
	return true
}

func (c *countedPacketConnection) WriterReplaceable() bool {
	return true
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })
	if err = extractAssets(); err != nil {
		return nil, fmt.Errorf("prepare rule assets: %w", err)
	}

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:          instance,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}
	b.Router().AppendTracker(&connectionCounter{
		activeTCP: &b.activeTCP,
		activeUDP: &b.activeUDP,
	})

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// clear main instance
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

func (b *BoxInstance) ActiveTCPConnections() int64 {
	return b.activeTCP.Load()
}

func (b *BoxInstance) ActiveConnections() int64 {
	return b.activeTCP.Load() + b.activeUDP.Load()
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	// test i
	if i != nil {
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(i.Box), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test direct
	if mainInstance == nil {
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(nil), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test mainInstance
	return speedtest.UrlTest(boxapi.CreateProxyHttpClient(mainInstance.Box), link, timeout, speedtest.UrlTestStandard_RTT)
}

// UrlTestDownload measures a bounded download through the selected box. It
// intentionally returns JSON to keep the gomobile ABI stable while exposing
// both byte count and elapsed time to the Android UI.
func UrlTestDownload(i *BoxInstance, link string, timeout int64, maxBytes int64) (result string, err error) {
	defer device.DeferPanicToError("box.UrlTestDownload", func(err_ error) { err = err_ })
	if maxBytes <= 0 || maxBytes > 8<<20 {
		return "", errors.New("invalid download size")
	}
	if timeout <= 0 || timeout > 60_000 {
		return "", errors.New("invalid download timeout")
	}
	var client *http.Client
	if i != nil {
		client = boxapi.CreateProxyHttpClient(i.Box)
	} else if mainInstance == nil {
		client = boxapi.CreateProxyHttpClient(nil)
	} else {
		client = boxapi.CreateProxyHttpClient(mainInstance.Box)
	}
	defer client.CloseIdleConnections()
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return "", err
	}
	started := time.Now()
	response, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		return "", fmt.Errorf("HTTP %s", response.Status)
	}
	bytesRead, err := io.Copy(io.Discard, io.LimitReader(response.Body, maxBytes+1))
	if err != nil {
		return "", err
	}
	if bytesRead <= 0 {
		return "", errors.New("download response is empty")
	}
	if bytesRead > maxBytes {
		return "", fmt.Errorf("download response exceeds %d bytes", maxBytes)
	}
	encoded, err := json.Marshal(map[string]any{
		"bytes":     bytesRead,
		"elapsedMs": max(int64(1), time.Since(started).Milliseconds()),
	})
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
