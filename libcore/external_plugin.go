package libcore

import (
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// BuildExternalPluginConfig owns the portable configuration for legacy helper
// processes. Android remains responsible for locating and starting executables.
func BuildExternalPluginConfig(kind, profileJSON string, localPort int32, finalAddress string, finalPort int32, logLevel int32, ipv6Mode int32, certificatePath string) (string, error) {
	var profile map[string]any
	decoder := json.NewDecoder(strings.NewReader(profileJSON))
	decoder.UseNumber()
	if err := decoder.Decode(&profile); err != nil {
		return "", fmt.Errorf("decode external profile: %w", err)
	}
	if finalAddress == "" {
		finalAddress = anyString(profile["serverAddress"])
	}
	if finalPort <= 0 {
		finalPort = int32(anyInt(profile["serverPort"], 0))
	}
	var config map[string]any
	switch kind {
	case "trojan-go":
		config = buildTrojanGoPlugin(profile, int(localPort), finalAddress, int(finalPort), int(logLevel), int(ipv6Mode))
	case "mieru":
		config = buildMieruPlugin(profile, int(localPort), finalAddress, int(finalPort))
	case "naive":
		config = buildNaivePlugin(profile, int(localPort), finalAddress, int(finalPort), int(logLevel))
	case "hysteria":
		var err error
		config, err = buildHysteriaOnePlugin(profile, int(localPort), finalAddress, int(finalPort), certificatePath)
		if err != nil {
			return "", err
		}
	default:
		return "", fmt.Errorf("unsupported external plugin kind %s", kind)
	}
	encoded, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return "", fmt.Errorf("encode external plugin config: %w", err)
	}
	return string(encoded), nil
}

func buildTrojanGoPlugin(profile map[string]any, localPort int, finalAddress string, finalPort, logLevel, ipv6Mode int) map[string]any {
	serverAddress := anyString(profile["serverAddress"])
	sni := anyString(profile["sni"])
	if sni == "" && finalAddress == configLocalhost && net.ParseIP(serverAddress) == nil {
		sni = serverAddress
	}
	config := map[string]any{
		"run_type": "client", "local_addr": configLocalhost, "local_port": localPort,
		"remote_addr": finalAddress, "remote_port": finalPort,
		"password":  []string{anyString(profile["password"])},
		"log_level": map[bool]int{true: 0, false: 2}[logLevel > 0],
		"tcp":       map[string]any{"prefer_ipv4": ipv6Mode <= 1},
	}
	if anyString(profile["type"]) == "ws" {
		config["websocket"] = map[string]any{
			"enabled": true, "host": anyString(profile["host"]), "path": anyString(profile["path"]),
		}
	}
	ssl := map[string]any{}
	putNonEmpty(ssl, "sni", sni)
	if anyBool(profile["allowInsecure"]) {
		ssl["verify"] = false
	}
	config["ssl"] = ssl
	if encryption := anyString(profile["encryption"]); strings.HasPrefix(encryption, "ss;") {
		methodPassword := strings.TrimPrefix(encryption, "ss;")
		parts := strings.SplitN(methodPassword, ":", 2)
		if len(parts) == 2 {
			config["shadowsocks"] = map[string]any{"enabled": true, "method": parts[0], "password": parts[1]}
		}
	}
	return config
}

func buildMieruPlugin(profile map[string]any, localPort int, finalAddress string, finalPort int) map[string]any {
	return map[string]any{
		"activeProfile": "default", "socks5Port": localPort, "loggingLevel": "INFO",
		"profiles": []any{map[string]any{
			"profileName": "default",
			"user":        map[string]any{"name": anyString(profile["username"]), "password": anyString(profile["password"])},
			"servers": []any{map[string]any{
				"ipAddress":    finalAddress,
				"portBindings": []any{map[string]any{"port": finalPort, "protocol": anyString(profile["protocol"])}},
			}},
			"mtu": anyInt(profile["mtu"], 1400),
		}},
	}
}

func buildNaivePlugin(profile map[string]any, localPort int, finalAddress string, finalPort, logLevel int) map[string]any {
	serverAddress := anyString(profile["serverAddress"])
	sni := anyString(profile["sni"])
	resolverName := serverAddress
	proxyHost := finalAddress
	if sni != "" {
		resolverName = sni
		proxyHost = sni
	} else if net.ParseIP(serverAddress) == nil {
		proxyHost = serverAddress
	}
	proxyURL := &url.URL{
		Scheme: defaultString(anyString(profile["proto"]), "https"),
		Host:   net.JoinHostPort(proxyHost, strconv.Itoa(finalPort)),
	}
	username := anyString(profile["username"])
	password := anyString(profile["password"])
	if username != "" || password != "" {
		proxyURL.User = url.UserPassword(username, password)
	}
	config := map[string]any{
		"listen": "socks://" + net.JoinHostPort(configLocalhost, strconv.Itoa(localPort)),
		"proxy":  proxyURL.String(),
	}
	if resolverName != "" && finalAddress != "" && (sni != "" || net.ParseIP(serverAddress) == nil) {
		config["host-resolver-rules"] = "MAP " + resolverName + " " + finalAddress
	}
	if headers := anyString(profile["extraHeaders"]); headers != "" {
		config["extra-headers"] = strings.ReplaceAll(headers, "\n", "\r\n")
	}
	if logLevel > 0 {
		config["log"] = ""
	}
	if concurrency := anyInt(profile["insecureConcurrency"], 0); concurrency > 0 {
		config["insecure-concurrency"] = concurrency
	}
	return config
}

func buildHysteriaOnePlugin(profile map[string]any, localPort int, finalAddress string, finalPort int, certificatePath string) (map[string]any, error) {
	if anyInt(profile["protocolVersion"], 1) != 1 {
		return nil, fmt.Errorf("external Hysteria requires protocol version 1")
	}
	serverAddress := anyString(profile["serverAddress"])
	sni := anyString(profile["sni"])
	if sni == "" && finalAddress == configLocalhost && net.ParseIP(serverAddress) == nil {
		sni = serverAddress
	}
	config := map[string]any{
		"server":  net.JoinHostPort(finalAddress, strconv.Itoa(finalPort)),
		"up_mbps": anyInt(profile["uploadMbps"], 10), "down_mbps": anyInt(profile["downloadMbps"], 50),
		"socks5": map[string]any{"listen": net.JoinHostPort(configLocalhost, strconv.Itoa(localPort))},
		"retry":  5, "fast_open": true, "lazy_start": true,
		"obfs": anyString(profile["obfuscation"]), "hop_interval": anyInt(profile["hopInterval"], 10),
	}
	switch anyInt(profile["protocol"], 0) {
	case 1:
		config["protocol"] = "faketcp"
	case 2:
		config["protocol"] = "wechat-video"
	}
	switch anyInt(profile["authPayloadType"], 0) {
	case 1:
		config["auth_str"] = anyString(profile["authPayload"])
	case 2:
		config["auth"] = anyString(profile["authPayload"])
	}
	putNonEmpty(config, "server_name", sni)
	putNonEmpty(config, "alpn", anyString(profile["alpn"]))
	if anyString(profile["caText"]) != "" {
		// Runtime callers materialize caText and pass its path. Export callers do
		// not have a durable Android cache path, matching the legacy exporter by
		// omitting the path-only field from the concatenated example config.
		if certificatePath != "" {
			config["ca"] = certificatePath
		}
	}
	if anyBool(profile["allowInsecure"]) {
		config["insecure"] = true
	}
	if value := anyInt(profile["streamReceiveWindow"], 0); value > 0 {
		config["recv_window_conn"] = value
	}
	if value := anyInt(profile["connectionReceiveWindow"], 0); value > 0 {
		config["recv_window"] = value
	}
	if anyBool(profile["disableMtuDiscovery"]) {
		config["disable_mtu_discovery"] = true
	}
	return config, nil
}
