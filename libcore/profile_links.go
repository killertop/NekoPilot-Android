package libcore

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
)

const maxProfileLinkCount = 10000

// EncodeProfileLink is the inverse portable boundary used by Android's share
// action. Database serialization remains in Kotlin/Java.
func EncodeProfileLink(kind, profileJSON string) (string, error) {
	var profile map[string]any
	decoder := json.NewDecoder(strings.NewReader(profileJSON))
	decoder.UseNumber()
	if err := decoder.Decode(&profile); err != nil {
		return "", fmt.Errorf("decode profile: %w", err)
	}
	if kind == "vmess" {
		payload := map[string]any{
			"v": "2", "ps": anyString(profile["name"]), "add": anyString(profile["serverAddress"]),
			"port": fmt.Sprint(anyInt(profile["serverPort"], 443)), "id": anyString(profile["uuid"]),
			"aid": fmt.Sprint(anyInt(profile["alterId"], 0)), "scy": anyString(profile["encryption"]),
			"net": anyString(profile["type"]), "host": anyString(profile["host"]), "path": anyString(profile["path"]),
			"tls": map[bool]string{true: "tls", false: ""}[anyString(profile["security"]) == "tls"],
			"sni": anyString(profile["sni"]), "alpn": strings.ReplaceAll(anyString(profile["alpn"]), "\n", ","),
			"fp": anyString(profile["utlsFingerprint"]),
		}
		if anyString(profile["realityPubKey"]) != "" {
			payload["tls"] = "reality"
			payload["pbk"] = anyString(profile["realityPubKey"])
			payload["sid"] = anyString(profile["realityShortId"])
		}
		encoded, _ := json.Marshal(payload)
		return "vmess://" + base64.RawStdEncoding.EncodeToString(encoded), nil
	}
	scheme := kind
	if kind == "ss" {
		credential := base64.RawURLEncoding.EncodeToString([]byte(anyString(profile["method"]) + ":" + anyString(profile["password"])))
		return buildEncodedURL("ss", credential, "", profile, url.Values{}), nil
	}
	query := url.Values{}
	username, password := "", ""
	switch kind {
	case "vless":
		username = anyString(profile["uuid"])
		addV2RayQuery(query, profile)
		putQuery(query, "flow", anyString(profile["encryption"]))
	case "trojan":
		username = anyString(profile["password"])
		addV2RayQuery(query, profile)
	case "socks":
		protocol := anyInt(profile["protocol"], 2)
		scheme = map[int]string{0: "socks4", 1: "socks4a", 2: "socks5"}[protocol]
		username, password = anyString(profile["username"]), anyString(profile["password"])
	case "http":
		if anyString(profile["security"]) == "tls" {
			scheme = "https"
		}
		username, password = anyString(profile["username"]), anyString(profile["password"])
		putQuery(query, "sni", anyString(profile["sni"]))
		putBoolQuery(query, "insecure", anyBool(profile["allowInsecure"]))
	case "naive":
		scheme = "naive+" + defaultString(anyString(profile["proto"]), "https")
		username, password = anyString(profile["username"]), anyString(profile["password"])
		putQuery(query, "sni", anyString(profile["sni"]))
		putQuery(query, "cert", anyString(profile["certificates"]))
		putQuery(query, "extra-headers", anyString(profile["extraHeaders"]))
		if concurrency := anyInt(profile["insecureConcurrency"], 0); concurrency > 0 {
			query.Set("insecure-concurrency", fmt.Sprint(concurrency))
		}
	case "trojan-go":
		username = anyString(profile["password"])
		putQuery(query, "sni", anyString(profile["sni"]))
		putQuery(query, "type", anyString(profile["type"]))
		putQuery(query, "host", anyString(profile["host"]))
		putQuery(query, "path", anyString(profile["path"]))
	case "tuic":
		username, password = anyString(profile["uuid"]), anyString(profile["token"])
		putQuery(query, "sni", anyString(profile["sni"]))
		putQuery(query, "alpn", strings.ReplaceAll(anyString(profile["alpn"]), "\n", ","))
		putQuery(query, "congestion_control", anyString(profile["congestionController"]))
		putQuery(query, "udp_relay_mode", anyString(profile["udpRelayMode"]))
		putBoolQuery(query, "allow_insecure", anyBool(profile["allowInsecure"]))
	case "anytls":
		username = anyString(profile["password"])
		putQuery(query, "sni", anyString(profile["sni"]))
		putQuery(query, "alpn", strings.ReplaceAll(anyString(profile["alpn"]), "\n", ","))
		putQuery(query, "fp", anyString(profile["utlsFingerprint"]))
		putBoolQuery(query, "insecure", anyBool(profile["allowInsecure"]))
	case "hysteria", "hysteria2":
		version := anyInt(profile["protocolVersion"], 2)
		if ports := anyString(profile["serverPorts"]); ports != "" {
			parts := strings.FieldsFunc(ports, func(r rune) bool { return r == ',' || r == ':' || r == '-' })
			if len(parts) > 0 {
				if parsedPort, err := strconv.Atoi(parts[0]); err == nil {
					profile["serverPort"] = parsedPort
				}
			}
		}
		if version == 2 {
			scheme = "hy2"
			credential := anyString(profile["authPayload"])
			if strings.Contains(credential, ":") {
				username, password = strings.SplitN(credential, ":", 2)[0], strings.SplitN(credential, ":", 2)[1]
			} else {
				username = credential
			}
			putQuery(query, "sni", anyString(profile["sni"]))
			if obfs := anyString(profile["obfuscation"]); obfs != "" {
				query.Set("obfs", "salamander")
				query.Set("obfs-password", obfs)
			}
		} else {
			scheme = "hysteria"
			putQuery(query, "auth", anyString(profile["authPayload"]))
			putQuery(query, "peer", anyString(profile["sni"]))
			query.Set("upmbps", fmt.Sprint(anyInt(profile["uploadMbps"], 10)))
			query.Set("downmbps", fmt.Sprint(anyInt(profile["downloadMbps"], 50)))
		}
		if ports := anyString(profile["serverPorts"]); strings.ContainsAny(ports, ",-:") {
			query.Set("mport", ports)
		}
		putBoolQuery(query, "insecure", anyBool(profile["allowInsecure"]))
	default:
		return "", fmt.Errorf("unsupported profile link kind %s", kind)
	}
	return buildEncodedURL(scheme, username, password, profile, query), nil
}

func buildEncodedURL(scheme, username, password string, profile map[string]any, query url.Values) string {
	host := net.JoinHostPort(anyString(profile["serverAddress"]), fmt.Sprint(anyInt(profile["serverPort"], 443)))
	parsed := &url.URL{Scheme: scheme, Host: host, Fragment: anyString(profile["name"]), RawQuery: query.Encode()}
	if username != "" || password != "" {
		if password != "" {
			parsed.User = url.UserPassword(username, password)
		} else {
			parsed.User = url.User(username)
		}
	}
	return parsed.String()
}

func addV2RayQuery(query url.Values, profile map[string]any) {
	putQuery(query, "type", anyString(profile["type"]))
	putQuery(query, "host", anyString(profile["host"]))
	putQuery(query, "path", anyString(profile["path"]))
	if anyString(profile["security"]) == "tls" {
		query.Set("security", "tls")
	}
	putQuery(query, "sni", anyString(profile["sni"]))
	putQuery(query, "alpn", strings.ReplaceAll(anyString(profile["alpn"]), "\n", ","))
	putQuery(query, "fp", anyString(profile["utlsFingerprint"]))
	putQuery(query, "pbk", anyString(profile["realityPubKey"]))
	putQuery(query, "sid", anyString(profile["realityShortId"]))
	putBoolQuery(query, "insecure", anyBool(profile["allowInsecure"]))
}

func putQuery(query url.Values, key, value string) {
	if value != "" {
		query.Set(key, value)
	}
}

func putBoolQuery(query url.Values, key string, value bool) {
	if value {
		query.Set(key, "1")
	}
}

// ParseProfileLinks parses portable share links in one Go call. Android keeps
// ownership of persistence and UI state; the returned objects intentionally
// use the existing Java bean field names to preserve the Room/Kryo ABI.
func ParseProfileLinks(input string) (string, error) {
	if len(input) > maxPortableConfigBytes {
		return "", fmt.Errorf("profile list is too large")
	}
	fields := strings.Fields(input)
	if len(fields) > maxProfileLinkCount {
		return "", fmt.Errorf("profile list contains too many links")
	}
	profiles := make([]map[string]any, 0, len(fields))
	for _, field := range fields {
		profile, err := parseProfileLink(field)
		if err == nil {
			profiles = append(profiles, profile)
		}
	}
	encoded, err := json.Marshal(profiles)
	return string(encoded), err
}

func parseProfileLink(link string) (map[string]any, error) {
	lower := strings.ToLower(link)
	switch {
	case strings.HasPrefix(lower, "vmess://"):
		return parseVMessLink(link)
	case strings.HasPrefix(lower, "vless://"):
		return parseV2RayURL(link, "vmess", false)
	case strings.HasPrefix(lower, "trojan://"):
		return parseV2RayURL(link, "trojan", true)
	case strings.HasPrefix(lower, "trojan-go://"):
		return parseTrojanGoLink(link)
	case strings.HasPrefix(lower, "ss://"):
		return parseShadowsocksLink(link)
	case strings.HasPrefix(lower, "socks://"), strings.HasPrefix(lower, "socks4://"),
		strings.HasPrefix(lower, "socks4a://"), strings.HasPrefix(lower, "socks5://"):
		return parseUserPasswordURL(link, "socks")
	case strings.HasPrefix(lower, "http://"), strings.HasPrefix(lower, "https://"):
		return parseUserPasswordURL(link, "http")
	case strings.HasPrefix(lower, "naive+http://"), strings.HasPrefix(lower, "naive+https://"):
		return parseUserPasswordURL(strings.TrimPrefix(link, "naive+"), "naive")
	case strings.HasPrefix(lower, "tuic://"):
		return parseTUICLink(link)
	case strings.HasPrefix(lower, "anytls://"):
		return parseAnyTLSLink(link)
	case strings.HasPrefix(lower, "hysteria://"), strings.HasPrefix(lower, "hysteria2://"), strings.HasPrefix(lower, "hy2://"):
		encoded, err := ParseHysteriaLink(link)
		if err != nil {
			return nil, err
		}
		var profile map[string]any
		if err = json.Unmarshal([]byte(encoded), &profile); err != nil {
			return nil, err
		}
		profile["kind"] = "hysteria"
		return profile, nil
	default:
		return nil, fmt.Errorf("unsupported profile link")
	}
}

func parseURL(link string) (*url.URL, error) {
	parsed, err := url.Parse(link)
	if err != nil || parsed.Hostname() == "" {
		return nil, fmt.Errorf("invalid profile URL")
	}
	return parsed, nil
}

func baseProfile(kind string, parsed *url.URL, defaultPort int) map[string]any {
	port, _ := strconv.Atoi(parsed.Port())
	if port == 0 {
		port = defaultPort
	}
	return map[string]any{
		"kind": kind, "serverAddress": parsed.Hostname(), "serverPort": port,
		"name": parsed.Fragment,
	}
}

func parseUserPasswordURL(link, kind string) (map[string]any, error) {
	parsed, err := parseURL(link)
	if err != nil {
		return nil, err
	}
	defaultPort := 1080
	if (kind == "http" || kind == "naive") && parsed.Scheme == "https" {
		defaultPort = 443
	} else if kind == "http" || kind == "naive" {
		defaultPort = 80
	}
	profile := baseProfile(kind, parsed, defaultPort)
	if kind == "http" && parsed.EscapedPath() != "" && parsed.EscapedPath() != "/" {
		return nil, fmt.Errorf("HTTP URL is not a proxy link")
	}
	if parsed.User != nil {
		profile["username"] = parsed.User.Username()
		profile["password"], _ = parsed.User.Password()
	}
	if kind == "socks" {
		profile["protocol"] = strings.TrimPrefix(strings.ToLower(parsed.Scheme), "socks")
	} else if kind == "http" {
		profile["security"] = map[bool]string{true: "tls", false: "none"}[parsed.Scheme == "https"]
		profile["sni"] = parsed.Query().Get("sni")
		profile["allowInsecure"] = queryBool(parsed.Query(), "insecure", "allowInsecure")
	} else if kind == "naive" {
		profile["proto"] = parsed.Scheme
		profile["sni"] = parsed.Query().Get("sni")
		profile["certificates"] = parsed.Query().Get("cert")
		profile["extraHeaders"] = parsed.Query().Get("extra-headers")
		profile["insecureConcurrency"] = queryInt(parsed.Query(), "insecure-concurrency")
	}
	return profile, nil
}

func parseV2RayURL(link, kind string, trojan bool) (map[string]any, error) {
	parsed, err := parseURL(link)
	if err != nil {
		return nil, err
	}
	profile := baseProfile(kind, parsed, 443)
	credential := ""
	if parsed.User != nil {
		credential = parsed.User.Username()
	}
	if trojan {
		profile["password"] = credential
	} else {
		profile["uuid"] = credential
		profile["alterId"] = -1
		profile["encryption"] = parsed.Query().Get("flow")
	}
	query := parsed.Query()
	profile["type"] = firstValue(query, "type", "network", "net")
	if profile["type"] == "" {
		profile["type"] = "tcp"
	}
	profile["host"] = firstValue(query, "host", "peer")
	profile["path"] = firstValue(query, "path", "serviceName", "service_name")
	security := firstValue(query, "security", "tls")
	if security == "tls" || security == "reality" || query.Get("pbk") != "" {
		profile["security"] = "tls"
	} else if trojan {
		profile["security"] = "tls"
	} else {
		profile["security"] = "none"
	}
	profile["sni"] = firstValue(query, "sni", "serverName", "peer")
	profile["alpn"] = strings.ReplaceAll(query.Get("alpn"), ",", "\n")
	profile["utlsFingerprint"] = firstValue(query, "fp", "fingerprint")
	profile["realityPubKey"] = firstValue(query, "pbk", "publicKey")
	profile["realityShortId"] = firstValue(query, "sid", "shortId")
	profile["allowInsecure"] = queryBool(query, "allowInsecure", "insecure")
	profile["wsMaxEarlyData"] = queryInt(query, "ed", "max-early-data")
	profile["earlyDataHeaderName"] = firstValue(query, "eh", "early-data-header-name")
	switch firstValue(query, "packetEncoding", "packet-encoding") {
	case "packetaddr":
		profile["packetEncoding"] = 1
	case "xudp":
		profile["packetEncoding"] = 2
	}
	return profile, nil
}

func parseVMessLink(link string) (map[string]any, error) {
	raw := strings.TrimPrefix(link, "vmess://")
	decoded, err := decodeBase64String(raw)
	if err != nil {
		return nil, err
	}
	var source map[string]any
	if err = json.Unmarshal(decoded, &source); err != nil {
		return nil, fmt.Errorf("unsupported VMess link: %w", err)
	}
	profile := map[string]any{
		"kind": "vmess", "name": stringValue(source, "ps"), "serverAddress": stringValue(source, "add"),
		"serverPort": intValue(source, "port", 443), "uuid": stringValue(source, "id"),
		"alterId": intValue(source, "aid", 0), "encryption": defaultString(stringValue(source, "scy"), "auto"),
		"type": defaultString(stringValue(source, "net"), "tcp"), "host": stringValue(source, "host"),
		"path": stringValue(source, "path"), "sni": stringValue(source, "sni"),
		"alpn":            strings.ReplaceAll(stringValue(source, "alpn"), ",", "\n"),
		"utlsFingerprint": stringValue(source, "fp"),
	}
	tls := stringValue(source, "tls")
	if tls == "tls" || tls == "reality" {
		profile["security"] = "tls"
		profile["realityPubKey"] = stringValue(source, "pbk")
		profile["realityShortId"] = stringValue(source, "sid")
	} else {
		profile["security"] = "none"
	}
	if profile["serverAddress"] == "" || profile["uuid"] == "" {
		return nil, fmt.Errorf("invalid VMess link")
	}
	return profile, nil
}

func parseShadowsocksLink(link string) (map[string]any, error) {
	raw := strings.TrimPrefix(link, "ss://")
	plugin := ""
	name := ""
	if index := strings.IndexByte(raw, '#'); index >= 0 {
		name, _ = url.PathUnescape(raw[index+1:])
		raw = raw[:index]
	}
	if index := strings.IndexByte(raw, '?'); index >= 0 {
		if values, err := url.ParseQuery(strings.SplitN(raw[index+1:], "#", 2)[0]); err == nil {
			plugin = values.Get("plugin")
		}
		raw = raw[:index]
	}
	if !strings.Contains(raw, "@") {
		decoded, err := decodeBase64String(raw)
		if err != nil {
			return nil, err
		}
		raw = string(decoded)
	} else {
		parts := strings.SplitN(raw, "@", 2)
		if !strings.Contains(parts[0], ":") {
			decoded, err := decodeBase64String(parts[0])
			if err != nil {
				return nil, err
			}
			raw = string(decoded) + "@" + parts[1]
		}
	}
	parsed, err := url.Parse("ss://" + raw)
	if err != nil || parsed.User == nil {
		return nil, fmt.Errorf("invalid Shadowsocks link")
	}
	password, _ := parsed.User.Password()
	profile := baseProfile("ss", parsed, 8388)
	profile["name"] = name
	profile["method"] = parsed.User.Username()
	profile["password"] = password
	profile["plugin"] = plugin
	return profile, nil
}

func parseTrojanGoLink(link string) (map[string]any, error) {
	parsed, err := parseURL(link)
	if err != nil {
		return nil, err
	}
	profile := baseProfile("trojan-go", parsed, 443)
	if parsed.User != nil {
		profile["password"] = parsed.User.Username()
	}
	query := parsed.Query()
	profile["sni"] = firstValue(query, "sni", "peer")
	profile["type"] = defaultString(query.Get("type"), "original")
	profile["host"] = query.Get("host")
	profile["path"] = query.Get("path")
	profile["allowInsecure"] = queryBool(query, "allowInsecure", "insecure")
	return profile, nil
}

func parseTUICLink(link string) (map[string]any, error) {
	parsed, err := parseURL(link)
	if err != nil {
		return nil, err
	}
	profile := baseProfile("tuic", parsed, 443)
	if parsed.User != nil {
		profile["uuid"] = parsed.User.Username()
		profile["token"], _ = parsed.User.Password()
	}
	query := parsed.Query()
	profile["sni"] = query.Get("sni")
	profile["alpn"] = strings.ReplaceAll(query.Get("alpn"), ",", "\n")
	profile["congestionController"] = firstValue(query, "congestion_control", "congestion-controller")
	profile["udpRelayMode"] = firstValue(query, "udp_relay_mode", "udp-relay-mode")
	profile["allowInsecure"] = queryBool(query, "allow_insecure", "insecure")
	profile["disableSNI"] = queryBool(query, "disable_sni")
	profile["reduceRTT"] = queryBool(query, "reduce_rtt", "zero_rtt_handshake")
	profile["protocolVersion"] = 5
	return profile, nil
}

func parseAnyTLSLink(link string) (map[string]any, error) {
	parsed, err := parseURL(link)
	if err != nil {
		return nil, err
	}
	profile := baseProfile("anytls", parsed, 443)
	if parsed.User != nil {
		profile["password"] = parsed.User.Username()
	}
	query := parsed.Query()
	profile["sni"] = query.Get("sni")
	profile["alpn"] = strings.ReplaceAll(query.Get("alpn"), ",", "\n")
	profile["utlsFingerprint"] = firstValue(query, "fp", "fingerprint")
	profile["allowInsecure"] = queryBool(query, "insecure", "allowInsecure")
	return profile, nil
}

func decodeBase64String(value string) ([]byte, error) {
	value = strings.TrimSpace(value)
	for _, encoding := range []*base64.Encoding{base64.RawURLEncoding, base64.URLEncoding, base64.RawStdEncoding, base64.StdEncoding} {
		if decoded, err := encoding.DecodeString(value); err == nil {
			return decoded, nil
		}
	}
	return nil, fmt.Errorf("invalid base64")
}

func firstValue(values url.Values, keys ...string) string {
	for _, key := range keys {
		if value := values.Get(key); value != "" {
			return value
		}
	}
	return ""
}

func queryBool(values url.Values, keys ...string) bool {
	value := strings.ToLower(firstValue(values, keys...))
	return value == "1" || value == "true" || value == "yes"
}

func queryInt(values url.Values, keys ...string) int {
	value, _ := strconv.Atoi(firstValue(values, keys...))
	return value
}

func stringValue(values map[string]any, key string) string {
	value := values[key]
	switch typed := value.(type) {
	case string:
		return typed
	case json.Number:
		return typed.String()
	case float64:
		return strconv.FormatFloat(typed, 'f', -1, 64)
	default:
		return ""
	}
}

func intValue(values map[string]any, key string, fallback int) int {
	value, err := strconv.Atoi(stringValue(values, key))
	if err != nil {
		return fallback
	}
	return value
}

func defaultString(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func splitHostPort(value string, fallback int) (string, int) {
	host, portText, err := net.SplitHostPort(value)
	if err != nil {
		return strings.Trim(value, "[]"), fallback
	}
	port, _ := strconv.Atoi(portText)
	return host, port
}
