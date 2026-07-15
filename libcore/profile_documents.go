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
	if len(input) > maxPortableConfigBytes {
		return "", fmt.Errorf("profile document is too large")
	}
	if profiles, ok := parseClashDocument(input); ok {
		return marshalProfiles(profiles)
	}
	if profiles, ok := parseJSONDocument(input); ok {
		return marshalProfiles(profiles)
	}
	if decoded, err := decodeBase64String(strings.TrimSpace(input)); err == nil {
		if encoded, parseErr := ParseProfileLinks(string(decoded)); parseErr == nil && encoded != "[]" {
			return encoded, nil
		}
	}
	return ParseProfileLinks(input)
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
		kind = "wg"
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
	return nil, false
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
