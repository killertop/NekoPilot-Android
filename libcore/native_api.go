package libcore

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/sagernet/sing-box/experimental/v2rayapi"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

const internalSpeedTestInbound = "nekopilot-speedtest-in"

type runtimeEndpoint struct {
	apiAddress    string
	proxyAddress  string
	proxyUsername string
	proxyPassword string
}

func allocateLoopbackAddress() (string, error) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	address := listener.Addr().String()
	if err = listener.Close(); err != nil {
		return "", err
	}
	return address, nil
}

func randomLoopbackCredential() (string, error) {
	bytes := make([]byte, 18)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return hex.EncodeToString(bytes), nil
}

// prepareRuntimeConfig adds two loopback-only facilities to every running box:
// an authenticated mixed inbound for node speed tests and sing-box's native
// V2Ray gRPC statistics service. Neither listener is visible on LAN.
func prepareRuntimeConfig(raw string) (string, runtimeEndpoint, error) {
	var config map[string]any
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.UseNumber()
	if err := decoder.Decode(&config); err != nil {
		return "", runtimeEndpoint{}, fmt.Errorf("decode runtime config: %w", err)
	}
	if decoder.More() {
		return "", runtimeEndpoint{}, fmt.Errorf("decode runtime config: trailing data")
	}
	apiAddress, err := allocateLoopbackAddress()
	if err != nil {
		return "", runtimeEndpoint{}, fmt.Errorf("allocate native API listener: %w", err)
	}
	proxyAddress, err := allocateLoopbackAddress()
	if err != nil {
		return "", runtimeEndpoint{}, fmt.Errorf("allocate speed-test listener: %w", err)
	}
	username, err := randomLoopbackCredential()
	if err != nil {
		return "", runtimeEndpoint{}, err
	}
	password, err := randomLoopbackCredential()
	if err != nil {
		return "", runtimeEndpoint{}, err
	}

	inbounds, _ := config["inbounds"].([]any)
	config["inbounds"] = append(inbounds, map[string]any{
		"type": "mixed", "tag": internalSpeedTestInbound,
		"listen": "127.0.0.1", "listen_port": portFromAddress(proxyAddress),
		"users": []any{map[string]any{"username": username, "password": password}},
	})

	experimental, _ := config["experimental"].(map[string]any)
	if experimental == nil {
		experimental = make(map[string]any)
		config["experimental"] = experimental
	}
	// Clash API/YACD is not part of NekoPilot. Remove a stale custom-config
	// fragment before handing the document to the unmodified upstream core so
	// it can never create a Clash service.
	delete(experimental, "clash_api")
	experimental["v2ray_api"] = map[string]any{
		"listen": apiAddress,
		"stats": map[string]any{
			"enabled":   true,
			"inbounds":  []string{internalSpeedTestInbound},
			"outbounds": []string{configTagProxy, configTagDirect, configTagBypass},
		},
	}

	encoded, err := json.Marshal(config)
	if err != nil {
		return "", runtimeEndpoint{}, fmt.Errorf("encode runtime config: %w", err)
	}
	return string(encoded), runtimeEndpoint{
		apiAddress: apiAddress, proxyAddress: proxyAddress,
		proxyUsername: username, proxyPassword: password,
	}, nil
}

func portFromAddress(address string) int {
	_, port, err := net.SplitHostPort(address)
	if err != nil {
		return 0
	}
	value, _ := strconv.Atoi(port)
	return value
}

func newRuntimeHTTPClient(endpoint runtimeEndpoint) *http.Client {
	transport := &http.Transport{
		TLSHandshakeTimeout:   3 * time.Second,
		ResponseHeaderTimeout: 3 * time.Second,
	}
	if endpoint.proxyAddress != "" {
		proxyURL := &url.URL{
			Scheme: "http", Host: endpoint.proxyAddress,
			User: url.UserPassword(endpoint.proxyUsername, endpoint.proxyPassword),
		}
		transport.Proxy = http.ProxyURL(proxyURL)
	}
	return &http.Client{Transport: transport}
}

func (b *BoxInstance) nativeTrafficBytes(ctx context.Context) (int64, error) {
	if b == nil || b.nativeAPIAddress == "" {
		return 0, nil
	}
	connection, err := grpc.DialContext(ctx, b.nativeAPIAddress,
		grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithBlock())
	if err != nil {
		return 0, err
	}
	defer connection.Close()
	request := &v2rayapi.QueryStatsRequest{
		Patterns: []string{"inbound>>>" + internalSpeedTestInbound + ">>>traffic>>>"},
	}
	response := new(v2rayapi.QueryStatsResponse)
	// sing-box exposes the upstream V2Ray service name for compatibility. The
	// generated package name is intentionally different, so invoke the native
	// gRPC method by the advertised service path rather than its Go helper.
	err = connection.Invoke(ctx, "/v2ray.core.app.stats.command.StatsService/QueryStats", request, response)
	if err != nil {
		return 0, err
	}
	var total int64
	for _, stat := range response.Stat {
		total += stat.Value
	}
	return total, nil
}
