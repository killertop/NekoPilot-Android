package libcore

import (
	"encoding/json"
	"fmt"
	"strings"

	"gopkg.in/yaml.v3"
)

// ParseProfileDocument normalizes Clash YAML, sing-box JSON and encoded link
// lists into the same portable DTO used by ParseProfileLinks.
func ParseProfileDocument(input string) (string, error) {
	return parseProfileDocument(input, true)
}

func parseProfileDocument(input string, allowBase64 bool) (string, error) {
	if len(input) > maxPortableConfigBytes {
		return "", fmt.Errorf("profile document is too large")
	}
	if profiles, ok := parseClashDocument(input); ok {
		return marshalProfiles(profiles)
	}
	if profiles, ok := parseJSONDocument(input); ok {
		return marshalProfiles(profiles)
	}
	if strings.Contains(input, "[Interface]") {
		if encoded, err := ParseWireGuardConfig(input); err == nil {
			var profiles []map[string]any
			if json.Unmarshal([]byte(encoded), &profiles) == nil {
				for _, profile := range profiles {
					profile["kind"] = "wireguard"
				}
				return marshalProfiles(profiles)
			}
		}
	}
	if profile, ok := parseHysteriaDocument(input); ok {
		return marshalProfiles([]map[string]any{profile})
	}
	if allowBase64 {
		if decoded, err := decodeBase64String(strings.TrimSpace(input)); err == nil {
			if encoded, parseErr := parseProfileDocument(string(decoded), false); parseErr == nil && encoded != "[]" {
				return encoded, nil
			}
		}
	}
	if encoded, err := ParseProfileLinks(input); err == nil && encoded != "[]" {
		return encoded, nil
	}
	return "", fmt.Errorf("unsupported profile document")
}

func parseClashDocument(input string) ([]map[string]any, bool) {
	var document map[string]any
	if yaml.Unmarshal([]byte(input), &document) != nil {
		return nil, false
	}
	rawProfiles, ok := document["proxies"].([]any)
	if !ok || len(rawProfiles) == 0 || len(rawProfiles) > maxProfileLinkCount {
		return nil, false
	}
	fingerprint := anyString(document["global-client-fingerprint"])
	profiles := make([]map[string]any, 0, len(rawProfiles))
	for _, raw := range rawProfiles {
		entry, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		profile, err := clashProfile(entry, fingerprint)
		if err != nil {
			// Never return a partial subscription. Kotlin's compatibility parser
			// remains the fallback until this format is fully represented.
			return nil, false
		}
		profiles = append(profiles, profile)
	}
	return profiles, len(profiles) > 0
}

func clashProfile(entry map[string]any, globalFingerprint string) (map[string]any, error) {
	typeName := strings.ToLower(anyString(entry["type"]))
	kind := typeName
	switch typeName {
	case "socks5":
		kind = "socks"
	case "vless", "vmess":
		kind = "vmess"
	case "hysteria2":
		kind = "hysteria"
	case "wireguard":
		kind = "wireguard"
	}
	profile := map[string]any{
		"kind": kind, "name": anyString(entry["name"]), "serverAddress": anyString(entry["server"]),
		"serverPort": anyInt(entry["port"], 443),
	}
	if profile["serverAddress"] == "" {
		return nil, fmt.Errorf("missing server")
	}
	switch typeName {
	case "socks5", "http":
		profile["username"] = anyString(entry["username"])
		profile["password"] = anyString(entry["password"])
		if typeName == "socks5" {
			profile["protocol"] = "5"
		} else if anyBool(entry["tls"]) {
			profile["security"] = "tls"
			profile["sni"] = anyString(entry["sni"])
			profile["allowInsecure"] = anyBool(entry["skip-cert-verify"])
		}
	case "ss":
		profile["method"] = anyString(entry["cipher"])
		profile["password"] = anyString(entry["password"])
		plugin := anyString(entry["plugin"])
		if plugin != "" {
			profile["plugin"] = clashSSPlugin(plugin, anyMap(entry["plugin-opts"]))
		}
	case "vmess", "vless", "trojan":
		if typeName == "trojan" {
			profile["password"] = anyString(entry["password"])
		} else {
			profile["uuid"] = anyString(entry["uuid"])
			if typeName == "vless" {
				profile["alterId"] = -1
				profile["encryption"] = anyString(entry["flow"])
			} else {
				profile["alterId"] = anyInt(entry["alterId"], 0)
				profile["encryption"] = defaultString(anyString(entry["cipher"]), "auto")
			}
		}
		applyClashV2Ray(profile, entry, globalFingerprint)
	case "anytls":
		profile["password"] = anyString(entry["password"])
		profile["sni"] = anyString(entry["sni"])
		profile["alpn"] = anyStringList(entry["alpn"])
		profile["utlsFingerprint"] = defaultString(anyString(entry["client-fingerprint"]), globalFingerprint)
		profile["allowInsecure"] = anyBool(entry["skip-cert-verify"])
	case "hysteria", "hysteria2":
		profile["protocolVersion"] = map[bool]int{true: 2, false: 1}[typeName == "hysteria2"]
		profile["serverPorts"] = defaultString(anyString(entry["ports"]), fmt.Sprint(profile["serverPort"]))
		profile["sni"] = anyString(entry["sni"])
		profile["allowInsecure"] = anyBool(entry["skip-cert-verify"])
		profile["uploadMbps"] = bandwidthInt(entry["up"])
		profile["downloadMbps"] = bandwidthInt(entry["down"])
		profile["obfuscation"] = anyString(entry["obfs-password"])
		if typeName == "hysteria" {
			profile["authPayload"] = anyString(entry["auth-str"])
			profile["authPayloadType"] = 1
			profile["obfuscation"] = anyString(entry["obfs"])
		} else {
			profile["authPayload"] = anyString(entry["password"])
			profile["authPayloadType"] = 1
		}
	case "tuic":
		profile["uuid"] = anyString(entry["uuid"])
		profile["token"] = defaultString(anyString(entry["password"]), anyString(entry["token"]))
		profile["sni"] = anyString(entry["sni"])
		profile["alpn"] = anyStringList(entry["alpn"])
		profile["allowInsecure"] = anyBool(entry["skip-cert-verify"])
		profile["disableSNI"] = anyBool(entry["disable-sni"])
		profile["reduceRTT"] = anyBool(entry["reduce-rtt"])
		profile["congestionController"] = anyString(entry["congestion-controller"])
		profile["udpRelayMode"] = anyString(entry["udp-relay-mode"])
		profile["protocolVersion"] = 5
		if ip := anyString(entry["ip"]); ip != "" {
			profile["serverAddress"] = ip
		}
	case "wireguard":
		addresses := append(splitNonEmpty(anyStringList(entry["ip"])), splitNonEmpty(anyStringList(entry["ipv6"]))...)
		profile["localAddress"] = strings.Join(addresses, "\n")
		profile["privateKey"] = anyString(entry["private-key"])
		profile["peerPublicKey"] = anyString(entry["public-key"])
		profile["peerPreSharedKey"] = anyString(entry["pre-shared-key"])
		profile["mtu"] = anyInt(entry["mtu"], 1420)
		profile["reserved"] = anyStringList(entry["reserved"])
	case "ssh":
		profile["username"] = anyString(entry["username"])
		profile["password"] = anyString(entry["password"])
		profile["privateKey"] = anyString(entry["private-key"])
		profile["privateKeyPassphrase"] = anyString(entry["private-key-passphrase"])
		if profile["privateKey"] != "" {
			profile["authType"] = 2
		} else if profile["password"] != "" {
			profile["authType"] = 1
		} else {
			profile["authType"] = 0
		}
	case "mieru":
		profile["protocol"] = strings.ToUpper(defaultString(anyString(entry["transport"]), anyString(entry["protocol"])))
		profile["username"] = anyString(entry["username"])
		profile["password"] = anyString(entry["password"])
		profile["mtu"] = anyInt(entry["mtu"], 1400)
	case "shadowtls":
		profile["version"] = anyInt(entry["version"], 3)
		profile["password"] = anyString(entry["password"])
		profile["sni"] = anyString(entry["sni"])
		profile["security"] = "tls"
		profile["allowInsecure"] = anyBool(entry["skip-cert-verify"])
	default:
		return nil, fmt.Errorf("unsupported Clash profile type %s", typeName)
	}
	return profile, nil
}

func applyClashV2Ray(profile, entry map[string]any, globalFingerprint string) {
	if anyBool(entry["tls"]) || entry["reality-opts"] != nil {
		profile["security"] = "tls"
	} else if profile["kind"] == "trojan" {
		profile["security"] = "tls"
	} else {
		profile["security"] = "none"
	}
	profile["sni"] = anyString(entry["servername"])
	profile["alpn"] = anyStringList(entry["alpn"])
	profile["allowInsecure"] = anyBool(entry["skip-cert-verify"])
	profile["utlsFingerprint"] = defaultString(anyString(entry["client-fingerprint"]), globalFingerprint)
	profile["type"] = defaultString(anyString(entry["network"]), "tcp")
	if reality := anyMap(entry["reality-opts"]); reality != nil {
		profile["realityPubKey"] = anyString(reality["public-key"])
		profile["realityShortId"] = anyString(reality["short-id"])
	}
	if ws := anyMap(entry["ws-opts"]); ws != nil {
		profile["path"] = anyString(ws["path"])
		profile["wsMaxEarlyData"] = anyInt(ws["max-early-data"], 0)
		profile["earlyDataHeaderName"] = anyString(ws["early-data-header-name"])
		if headers := anyMap(ws["headers"]); headers != nil {
			profile["host"] = anyString(headers["Host"])
			if profile["host"] == "" {
				profile["host"] = anyString(headers["host"])
			}
		}
	}
	if grpc := anyMap(entry["grpc-opts"]); grpc != nil {
		profile["path"] = anyString(grpc["grpc-service-name"])
	}
	if h2 := anyMap(entry["h2-opts"]); h2 != nil {
		profile["type"] = "http"
		profile["host"] = anyStringList(h2["host"])
		profile["path"] = anyString(h2["path"])
	}
	if httpOptions := anyMap(entry["http-opts"]); httpOptions != nil {
		profile["type"] = "http"
		profile["path"] = anyStringList(httpOptions["path"])
		if headers := anyMap(httpOptions["headers"]); headers != nil {
			profile["host"] = anyStringList(headers["Host"])
		}
	}
	if smux := anyMap(entry["smux"]); smux != nil {
		profile["enableMux"] = anyBool(smux["enabled"])
		profile["muxConcurrency"] = anyInt(smux["max-streams"], 1)
		profile["muxPadding"] = anyBool(smux["padding"])
	}
	if ech := anyMap(entry["ech-opts"]); ech != nil {
		profile["enableECH"] = anyBool(ech["enable"])
		profile["echConfig"] = anyStringList(ech["config"])
	}
	if packet := anyString(entry["packet-encoding"]); packet == "packetaddr" {
		profile["packetEncoding"] = 1
	} else if packet == "xudp" {
		profile["packetEncoding"] = 2
	}
}

func parseJSONDocument(input string) ([]map[string]any, bool) {
	var value any
	decoder := json.NewDecoder(strings.NewReader(input))
	decoder.UseNumber()
	if decoder.Decode(&value) != nil {
		return nil, false
	}
	if values, ok := value.([]any); ok {
		profiles := make([]map[string]any, 0, len(values))
		for _, raw := range values {
			encoded, _ := json.Marshal(raw)
			if parsed, parsedOK := parseJSONDocument(string(encoded)); parsedOK {
				profiles = append(profiles, parsed...)
			}
		}
		return profiles, len(profiles) > 0
	}
	object, ok := value.(map[string]any)
	if !ok {
		return nil, false
	}
	if outbounds, ok := object["outbounds"].([]any); ok {
		profiles := make([]map[string]any, 0, len(outbounds))
		for _, raw := range outbounds {
			outbound, ok := raw.(map[string]any)
			if !ok {
				continue
			}
			typeName := anyString(outbound["type"])
			if typeName == "" || typeName == "direct" || typeName == "block" || typeName == "dns" || typeName == "selector" || typeName == "urltest" {
				continue
			}
			encoded, _ := json.Marshal(outbound)
			profiles = append(profiles, map[string]any{"kind": "config", "name": anyString(outbound["tag"]), "type": 1, "config": string(encoded)})
		}
		return profiles, len(profiles) > 0
	}
	if anyString(object["server"]) != "" && object["server_port"] != nil {
		encoded, _ := json.Marshal(object)
		return []map[string]any{{"kind": "config", "type": 1, "config": string(encoded)}}, true
	}
	if object["method"] != nil && object["server"] != nil {
		plugin := anyString(object["plugin"])
		if opts := anyString(object["plugin_opts"]); plugin != "" && opts != "" {
			plugin += ";" + opts
		}
		return []map[string]any{{
			"kind": "ss", "serverAddress": anyString(object["server"]), "serverPort": anyInt(object["server_port"], 8388),
			"method": anyString(object["method"]), "password": anyString(object["password"]), "plugin": plugin, "name": anyString(object["remarks"]),
		}}, true
	}
	if object["remote_addr"] != nil {
		profile := map[string]any{
			"kind": "trojan-go", "serverAddress": anyString(object["remote_addr"]), "serverPort": anyInt(object["remote_port"], 443),
		}
		switch password := object["password"].(type) {
		case string:
			profile["password"] = password
		case []any:
			if len(password) > 0 {
				profile["password"] = anyString(password[0])
			}
		}
		if ssl := anyMap(object["ssl"]); ssl != nil {
			profile["sni"] = anyString(ssl["sni"])
			profile["allowInsecure"] = ssl["verify"] == false
		}
		if ws := anyMap(object["websocket"]); ws != nil && anyBool(ws["enabled"]) {
			profile["type"] = "ws"
			profile["host"] = anyString(ws["host"])
			profile["path"] = anyString(ws["path"])
		}
		if ss := anyMap(object["shadowsocks"]); ss != nil && anyBool(ss["enabled"]) {
			profile["encryption"] = "ss;" + anyString(ss["method"]) + ":" + anyString(ss["password"])
		}
		return []map[string]any{profile}, profile["serverAddress"] != ""
	}
	if profile, ok := hysteriaObject(object); ok {
		return []map[string]any{profile}, true
	}
	return nil, false
}

func parseHysteriaDocument(input string) (map[string]any, bool) {
	var object map[string]any
	if yaml.Unmarshal([]byte(input), &object) != nil {
		return nil, false
	}
	return hysteriaObject(object)
}

func hysteriaObject(object map[string]any) (map[string]any, bool) {
	server := anyString(object["server"])
	if server == "" || (object["up"] == nil && object["up_mbps"] == nil && object["auth"] == nil && object["tls"] == nil && object["bandwidth"] == nil && object["quic"] == nil && object["obfs"] == nil) {
		return nil, false
	}
	host, ports := splitHysteriaServer(server)
	version2 := object["tls"] != nil || object["bandwidth"] != nil || object["quic"] != nil || object["obfs"] != nil || strings.EqualFold(anyString(object["version"]), "2")
	profile := map[string]any{"kind": "hysteria", "protocolVersion": 1, "serverAddress": host, "serverPorts": ports, "serverPort": firstPort(ports), "name": anyString(object["name"])}
	if version2 {
		profile["protocolVersion"] = 2
		profile["authPayloadType"] = 1
		profile["authPayload"] = defaultString(anyString(object["auth"]), anyString(object["password"]))
		if tls := anyMap(object["tls"]); tls != nil {
			profile["sni"] = defaultString(anyString(tls["sni"]), anyString(tls["serverName"]))
			profile["allowInsecure"] = anyBool(tls["insecure"])
			profile["caText"] = anyString(tls["ca"])
		}
		if obfs := anyMap(object["obfs"]); obfs != nil {
			if salamander := anyMap(obfs["salamander"]); salamander != nil {
				profile["obfuscation"] = anyString(salamander["password"])
			} else {
				profile["obfuscation"] = anyString(obfs["password"])
			}
		}
		if bandwidth := anyMap(object["bandwidth"]); bandwidth != nil {
			profile["uploadMbps"] = bandwidthInt(bandwidth["up"])
			profile["downloadMbps"] = bandwidthInt(bandwidth["down"])
		}
		if quic := anyMap(object["quic"]); quic != nil {
			profile["streamReceiveWindow"] = bandwidthInt(quic["initStreamReceiveWindow"])
			profile["connectionReceiveWindow"] = bandwidthInt(quic["initConnReceiveWindow"])
			profile["disableMtuDiscovery"] = anyBool(quic["disablePathMTUDiscovery"])
		}
		profile["hopInterval"] = anyInt(object["hopInterval"], 10)
		return profile, host != ""
	}
	profile["uploadMbps"] = anyInt(object["up_mbps"], bandwidthInt(object["up"]))
	profile["downloadMbps"] = anyInt(object["down_mbps"], bandwidthInt(object["down"]))
	profile["obfuscation"] = anyString(object["obfs"])
	if auth := anyString(object["auth_str"]); auth != "" {
		profile["authPayloadType"] = 1
		profile["authPayload"] = auth
	} else if auth = anyString(object["auth"]); auth != "" {
		profile["authPayloadType"] = 2
		profile["authPayload"] = auth
	}
	profile["sni"] = anyString(object["server_name"])
	profile["alpn"] = anyString(object["alpn"])
	profile["allowInsecure"] = anyBool(object["insecure"])
	profile["streamReceiveWindow"] = anyInt(object["recv_window_conn"], 0)
	profile["connectionReceiveWindow"] = anyInt(object["recv_window"], 0)
	profile["disableMtuDiscovery"] = anyBool(object["disable_mtu_discovery"])
	if protocol := anyString(object["protocol"]); protocol == "faketcp" {
		profile["protocol"] = 1
	} else if protocol == "wechat-video" {
		profile["protocol"] = 2
	}
	return profile, host != ""
}

func splitHysteriaServer(server string) (string, string) {
	server = strings.TrimSpace(server)
	if strings.HasPrefix(server, "[") {
		if end := strings.Index(server, "]"); end > 1 {
			return server[1:end], defaultString(strings.TrimPrefix(server[end+1:], ":"), "443")
		}
	}
	if index := strings.LastIndex(server, ":"); index > 0 && strings.Count(server, ":") == 1 {
		return server[:index], defaultString(server[index+1:], "443")
	}
	return server, "443"
}

func firstPort(ports string) int {
	part := strings.FieldsFunc(ports, func(r rune) bool { return r == ',' || r == ':' || r == '-' })
	if len(part) == 0 {
		return 443
	}
	return anyInt(part[0], 443)
}

func marshalProfiles(profiles []map[string]any) (string, error) {
	encoded, err := json.Marshal(profiles)
	return string(encoded), err
}

func clashSSPlugin(name string, options map[string]any) string {
	if options == nil {
		return name
	}
	parts := []string{name}
	for _, key := range []string{"mode", "host", "path"} {
		if value := anyString(options[key]); value != "" {
			parts = append(parts, key+"="+value)
		}
	}
	return strings.Join(parts, ";")
}

func anyMap(value any) map[string]any {
	result, _ := value.(map[string]any)
	return result
}

func anyString(value any) string {
	switch typed := value.(type) {
	case string:
		return typed
	case json.Number:
		return typed.String()
	case int:
		return fmt.Sprint(typed)
	case float64:
		return fmt.Sprint(typed)
	default:
		return ""
	}
}

func anyInt(value any, fallback int) int {
	parsed, err := strconvAtoi(anyString(value))
	if err != nil {
		return fallback
	}
	return parsed
}

func strconvAtoi(value string) (int, error) {
	var parsed int
	_, err := fmt.Sscan(value, &parsed)
	return parsed, err
}

func anyBool(value any) bool {
	switch typed := value.(type) {
	case bool:
		return typed
	case string:
		return typed == "true" || typed == "1"
	default:
		return false
	}
}

func anyStringList(value any) string {
	items, ok := value.([]any)
	if !ok {
		return anyString(value)
	}
	result := make([]string, 0, len(items))
	for _, item := range items {
		if text := anyString(item); text != "" {
			result = append(result, text)
		}
	}
	return strings.Join(result, "\n")
}

func bandwidthInt(value any) int {
	text := anyString(value)
	var parsed float64
	if _, err := fmt.Sscan(text, &parsed); err != nil || parsed < 0 {
		return 0
	}
	return int(parsed)
}
