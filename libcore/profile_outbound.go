package libcore

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
)

// BuildProfileOutbound converts a persisted Android profile bean to a sing-box
// outbound using the Go-owned configuration model boundary.
func BuildProfileOutbound(kind, profileJSON string, globalAllowInsecure bool) (string, error) {
	var profile map[string]any
	decoder := json.NewDecoder(strings.NewReader(profileJSON))
	decoder.UseNumber()
	if err := decoder.Decode(&profile); err != nil {
		return "", fmt.Errorf("decode profile: %w", err)
	}
	server := anyString(profile["serverAddress"])
	port := anyInt(profile["serverPort"], 0)
	outbound := map[string]any{"server": server, "server_port": port}
	switch kind {
	case "http", "vmess", "vless", "trojan":
		buildV2RayOutbound(outbound, kind, profile, globalAllowInsecure)
	case "shadowtls":
		outbound["type"] = "shadowtls"
		outbound["version"] = anyInt(profile["version"], 3)
		putNonEmpty(outbound, "password", anyString(profile["password"]))
		outbound["tls"] = buildTLS(profile, globalAllowInsecure)
	case "hysteria", "hysteria2":
		buildHysteriaOutbound(outbound, kind, profile, globalAllowInsecure)
	case "tuic":
		if anyInt(profile["protocolVersion"], 5) == 4 {
			return "", fmt.Errorf("TUIC v4 is no longer supported")
		}
		outbound["type"] = "tuic"
		outbound["uuid"] = anyString(profile["uuid"])
		outbound["password"] = anyString(profile["token"])
		outbound["congestion_control"] = anyString(profile["congestionController"])
		if anyString(profile["udpRelayMode"]) == "quic" {
			outbound["udp_relay_mode"] = "quic"
		}
		outbound["zero_rtt_handshake"] = anyBool(profile["reduceRTT"])
		outbound["tls"] = buildTLS(profile, globalAllowInsecure)
	case "socks":
		outbound["type"] = "socks"
		outbound["version"] = map[int]string{0: "4", 1: "4a", 2: "5"}[anyInt(profile["protocol"], 2)]
		putNonEmpty(outbound, "username", anyString(profile["username"]))
		putNonEmpty(outbound, "password", anyString(profile["password"]))
	case "ss":
		outbound["type"] = "shadowsocks"
		outbound["method"] = anyString(profile["method"])
		outbound["password"] = anyString(profile["password"])
		plugin := anyString(profile["plugin"])
		if plugin != "" && !strings.HasPrefix(plugin, "none") {
			outbound["plugin"] = strings.SplitN(plugin, ";", 2)[0]
			if parts := strings.SplitN(plugin, ";", 2); len(parts) == 2 {
				outbound["plugin_opts"] = parts[1]
			}
		}
	case "ssh":
		outbound["type"] = "ssh"
		outbound["user"] = anyString(profile["username"])
		if keys := splitNonEmpty(anyString(profile["publicKey"])); len(keys) > 0 {
			outbound["host_key"] = keys
		}
		if anyInt(profile["authType"], 1) == 2 {
			outbound["private_key"] = anyString(profile["privateKey"])
			putNonEmpty(outbound, "private_key_passphrase", anyString(profile["privateKeyPassphrase"]))
		} else {
			putNonEmpty(outbound, "password", anyString(profile["password"]))
		}
	case "anytls":
		outbound["type"] = "anytls"
		outbound["password"] = anyString(profile["password"])
		outbound["tls"] = buildTLS(profile, globalAllowInsecure)
	case "wireguard":
		// The local sing-box fork keeps the legacy WireGuard outbound ABI so
		// existing chains remain import-compatible while the Android option
		// mirror is removed.
		outbound["type"] = "wireguard"
		outbound["local_address"] = splitNonEmpty(anyString(profile["localAddress"]))
		outbound["private_key"] = anyString(profile["privateKey"])
		outbound["peer_public_key"] = anyString(profile["peerPublicKey"])
		putNonEmpty(outbound, "pre_shared_key", anyString(profile["peerPreSharedKey"]))
		outbound["mtu"] = anyInt(profile["mtu"], 1420)
		if reserved := anyString(profile["reserved"]); reserved != "" {
			outbound["reserved"] = normalizeWireGuardReserved(reserved)
		}
	default:
		return "", fmt.Errorf("unsupported outbound profile kind %s", kind)
	}
	encoded, err := json.Marshal(outbound)
	return string(encoded), err
}

func buildV2RayOutbound(outbound map[string]any, kind string, profile map[string]any, globalAllowInsecure bool) {
	outbound["type"] = kind
	switch kind {
	case "http":
		putNonEmpty(outbound, "username", anyString(profile["username"]))
		putNonEmpty(outbound, "password", anyString(profile["password"]))
	case "vmess":
		outbound["uuid"] = anyString(profile["uuid"])
		outbound["alter_id"] = anyInt(profile["alterId"], 0)
		outbound["security"] = defaultString(anyString(profile["encryption"]), "auto")
	case "vless":
		outbound["uuid"] = anyString(profile["uuid"])
		flow := anyString(profile["encryption"])
		if flow != "" && flow != "auto" {
			outbound["flow"] = flow
		}
	case "trojan":
		outbound["password"] = anyString(profile["password"])
	}
	if kind != "http" {
		if packet := anyInt(profile["packetEncoding"], 0); packet == 1 {
			outbound["packet_encoding"] = "packetaddr"
		} else if packet == 2 {
			outbound["packet_encoding"] = "xudp"
		}
		if transport := buildTransport(profile); transport != nil {
			outbound["transport"] = transport
		}
	}
	if anyString(profile["security"]) == "tls" {
		outbound["tls"] = buildTLS(profile, globalAllowInsecure)
	}
}

func buildTransport(profile map[string]any) map[string]any {
	typeName := anyString(profile["type"])
	transport := map[string]any{"type": typeName}
	switch typeName {
	case "", "tcp":
		return nil
	case "ws":
		path := defaultString(anyString(profile["path"]), "/")
		pathEarlyData := 0
		if marker := strings.LastIndex(path, "?ed="); marker >= 0 {
			pathEarlyData, _ = strconv.Atoi(path[marker+4:])
			if pathEarlyData <= 0 {
				pathEarlyData = 2048
			}
			path = path[:marker]
		}
		transport["path"] = path
		if host := anyString(profile["host"]); host != "" {
			transport["headers"] = map[string]string{"Host": host}
		}
		early := anyInt(profile["wsMaxEarlyData"], 0)
		if early <= 0 {
			early = pathEarlyData
		}
		if early > 0 {
			transport["max_early_data"] = early
			transport["early_data_header_name"] = defaultString(anyString(profile["earlyDataHeaderName"]), "Sec-WebSocket-Protocol")
		}
	case "http":
		transport["path"] = defaultString(anyString(profile["path"]), "/")
		if host := splitNonEmpty(anyString(profile["host"])); len(host) > 0 {
			transport["host"] = host
		}
		if anyString(profile["security"]) != "tls" {
			transport["method"] = "GET"
		}
	case "grpc":
		transport["service_name"] = anyString(profile["path"])
	case "httpupgrade":
		transport["host"] = anyString(profile["host"])
		transport["path"] = anyString(profile["path"])
	case "quic":
	default:
		return nil
	}
	return transport
}

func buildTLS(profile map[string]any, globalAllowInsecure bool) map[string]any {
	tls := map[string]any{"enabled": true}
	putNonEmpty(tls, "server_name", anyString(profile["sni"]))
	if alpn := splitNonEmpty(anyString(profile["alpn"])); len(alpn) > 0 {
		tls["alpn"] = alpn
	}
	putNonEmpty(tls, "certificate", firstNonEmpty(anyString(profile["certificates"]), anyString(profile["caText"])))
	tls["insecure"] = anyBool(profile["allowInsecure"]) || globalAllowInsecure
	if fingerprint := anyString(profile["utlsFingerprint"]); fingerprint != "" {
		tls["utls"] = map[string]any{"enabled": true, "fingerprint": fingerprint}
	}
	if publicKey := anyString(profile["realityPubKey"]); publicKey != "" {
		tls["reality"] = map[string]any{"enabled": true, "public_key": publicKey, "short_id": anyString(profile["realityShortId"])}
		if _, exists := tls["utls"]; !exists {
			tls["utls"] = map[string]any{"enabled": true, "fingerprint": "chrome"}
		}
	}
	echConfig := anyString(profile["echConfig"])
	_, hasEnableECH := profile["enableECH"]
	if anyBool(profile["enableECH"]) || !hasEnableECH && echConfig != "" {
		ech := map[string]any{"enabled": true}
		if echConfig != "" {
			ech["config"] = splitNonEmpty(echConfig)
		}
		tls["ech"] = ech
	}
	if anyBool(profile["disableSNI"]) {
		tls["disable_sni"] = true
	}
	return tls
}

func buildHysteriaOutbound(outbound map[string]any, kind string, profile map[string]any, globalAllowInsecure bool) {
	outbound["type"] = kind
	ports := anyString(profile["serverPorts"])
	if port, err := strconv.Atoi(ports); err == nil {
		outbound["server_port"] = port
	} else {
		outbound["server_ports"] = normalizePortRanges(ports)
	}
	outbound["hop_interval"] = fmt.Sprintf("%ds", anyInt(profile["hopInterval"], 10))
	outbound["up_mbps"] = anyInt(profile["uploadMbps"], 0)
	outbound["down_mbps"] = anyInt(profile["downloadMbps"], 0)
	if kind == "hysteria" {
		putNonEmpty(outbound, "obfs", anyString(profile["obfuscation"]))
		outbound["disable_mtu_discovery"] = anyBool(profile["disableMtuDiscovery"])
		if anyInt(profile["authPayloadType"], 0) == 2 {
			outbound["auth"] = anyString(profile["authPayload"])
		} else if anyInt(profile["authPayloadType"], 0) == 1 {
			outbound["auth_str"] = anyString(profile["authPayload"])
		}
		if window := anyInt(profile["streamReceiveWindow"], 0); window > 0 {
			outbound["recv_window_conn"] = window
		}
		if window := anyInt(profile["connectionReceiveWindow"], 0); window > 0 {
			outbound["recv_window"] = window
		}
	} else {
		outbound["password"] = anyString(profile["authPayload"])
		if password := anyString(profile["obfuscation"]); password != "" {
			outbound["obfs"] = map[string]any{"type": "salamander", "password": password}
		}
	}
	tls := buildTLS(profile, globalAllowInsecure)
	if kind == "hysteria2" {
		tls["alpn"] = []string{"h3"}
	}
	outbound["tls"] = tls
}

func putNonEmpty(target map[string]any, key, value string) {
	if value != "" {
		target[key] = value
	}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func splitNonEmpty(value string) []string {
	return strings.FieldsFunc(value, func(r rune) bool { return r == '\n' || r == '\r' || r == ',' })
}

func splitComma(value string) []string {
	result := strings.Split(value, ",")
	filtered := result[:0]
	for _, item := range result {
		if item = strings.TrimSpace(item); item != "" {
			filtered = append(filtered, item)
		}
	}
	return filtered
}

func normalizePortRanges(value string) []string {
	items := splitComma(value)
	for index := range items {
		items[index] = strings.ReplaceAll(items[index], "-", ":")
	}
	return items
}
