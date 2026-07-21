package libcore

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"gopkg.in/yaml.v3"
)

// ParseProfileDocument normalizes Clash YAML, sing-box JSON and encoded link
// lists into the same portable DTO used by ParseProfileLinks.
func ParseProfileDocument(input string) (string, error) {
	return parseProfileDocument(input, true)
}

// ParseSubscriptionDocument additionally reports Clash entries that could not be parsed.
// Android uses this metadata to keep the matching persisted nodes instead of treating a
// temporary malformed provider entry as an intentional deletion.
func ParseSubscriptionDocument(input string) (string, error) {
	return parseSubscriptionDocument(input, true)
}

func parseSubscriptionDocument(input string, allowBase64 bool) (string, error) {
	document, err := parseSubscriptionDocumentData(input, allowBase64)
	if err != nil {
		return "", err
	}
	return marshalSubscriptionDocument(
		document.profiles,
		document.skippedNames,
		document.hasUnnamedSkipped,
	)
}

type parsedSubscriptionData struct {
	profiles          []map[string]any
	skippedNames      []string
	hasUnnamedSkipped bool
}

func parseSubscriptionDocumentData(input string, allowBase64 bool) (parsedSubscriptionData, error) {
	if len(input) > maxPortableConfigBytes {
		return parsedSubscriptionData{}, fmt.Errorf("profile document is too large")
	}
	if _, err := validateBoundedJSONStructure([]byte(input)); err != nil {
		return parsedSubscriptionData{}, err
	}
	if profiles, ok, skippedNames, hasUnnamedSkipped, err := parseClashDocumentDetailed(input); ok {
		if err != nil {
			return parsedSubscriptionData{}, err
		}
		return parsedSubscriptionData{profiles, skippedNames, hasUnnamedSkipped}, nil
	}
	if profiles, hasSkipped, err := parseProfileLinksDetailed(input); err != nil {
		return parsedSubscriptionData{}, err
	} else if len(profiles) > 0 {
		return parsedSubscriptionData{profiles: profiles, hasUnnamedSkipped: hasSkipped}, nil
	}
	profiles, directErr := parseProfileDocumentMaps(input, false)
	if directErr == nil {
		return parsedSubscriptionData{profiles: profiles}, nil
	}
	if allowBase64 {
		if decoded, err := decodeBase64String(strings.TrimSpace(input)); err == nil {
			if parsed, parseErr := parseSubscriptionDocumentData(string(decoded), false); parseErr == nil {
				return parsed, nil
			}
		}
	}
	return parsedSubscriptionData{}, directErr
}

func marshalSubscriptionDocument(
	profiles []map[string]any,
	skippedNames []string,
	hasUnnamedSkipped bool,
) (string, error) {
	if len(profiles) > maxProfileLinkCount {
		return "", fmt.Errorf("too many subscription profiles")
	}
	// Keep the JNI JSON contract stable: Kotlin consumes these as arrays, and JSON null is not
	// interchangeable with an empty array for org.json.JSONArray.
	if profiles == nil {
		profiles = []map[string]any{}
	}
	if skippedNames == nil {
		skippedNames = []string{}
	}
	encoded, err := json.Marshal(map[string]any{
		"profiles":          profiles,
		"skippedNames":      skippedNames,
		"hasUnnamedSkipped": hasUnnamedSkipped,
	})
	return string(encoded), err
}

func parseProfileDocument(input string, allowBase64 bool) (string, error) {
	profiles, err := parseProfileDocumentMaps(input, allowBase64)
	if err != nil {
		return "", err
	}
	return marshalProfiles(profiles)
}

func parseProfileDocumentMaps(input string, allowBase64 bool) ([]map[string]any, error) {
	if len(input) > maxPortableConfigBytes {
		return nil, fmt.Errorf("profile document is too large")
	}
	isJSON, jsonErr := validateBoundedJSONStructure([]byte(input))
	if jsonErr != nil {
		return nil, jsonErr
	}
	if isJSON {
		if profiles, ok, err := parseJSONDocumentBytes([]byte(input), 0, false); ok {
			if err != nil {
				return nil, err
			}
			return profiles, nil
		}
	}
	if profiles, ok, err := parseClashDocument(input); ok {
		if err != nil {
			return nil, err
		}
		return profiles, nil
	}
	if strings.Contains(input, "[Interface]") {
		if encoded, err := ParseWireGuardConfig(input); err == nil {
			var profiles []map[string]any
			if json.Unmarshal([]byte(encoded), &profiles) == nil {
				for _, profile := range profiles {
					profile["kind"] = "wireguard"
				}
				return profiles, nil
			}
		}
	}
	if profile, ok := parseHysteriaDocument(input); ok {
		return []map[string]any{profile}, nil
	}
	if allowBase64 {
		if decoded, err := decodeBase64String(strings.TrimSpace(input)); err == nil {
			if profiles, parseErr := parseProfileDocumentMaps(string(decoded), false); parseErr == nil && len(profiles) > 0 {
				return profiles, nil
			}
		}
	}
	if profiles, _, err := parseProfileLinksDetailed(input); err == nil && len(profiles) > 0 {
		return profiles, nil
	}
	return nil, fmt.Errorf("unsupported profile document")
}

func parseClashDocument(input string) ([]map[string]any, bool, error) {
	profiles, ok, _, _, err := parseClashDocumentDetailed(input)
	return profiles, ok, err
}

func parseClashDocumentDetailed(input string) (
	profiles []map[string]any,
	ok bool,
	skippedNames []string,
	hasUnnamedSkipped bool,
	err error,
) {
	var document map[string]any
	if yaml.Unmarshal([]byte(input), &document) != nil {
		return nil, false, nil, false, nil
	}
	rawProfilesValue, isClash := document["proxies"]
	if !isClash {
		return nil, false, nil, false, nil
	}
	rawProfiles, ok := rawProfilesValue.([]any)
	if !ok {
		return nil, true, nil, false, fmt.Errorf("invalid Clash proxies list")
	}
	if len(rawProfiles) == 0 {
		return nil, true, nil, false, fmt.Errorf("Clash subscription contains no proxy entries")
	}
	if len(rawProfiles) > maxProfileLinkCount {
		return nil, true, nil, false, fmt.Errorf("Clash subscription contains too many proxy entries")
	}
	fingerprint := anyString(document["global-client-fingerprint"])
	profiles = make([]map[string]any, 0, len(rawProfiles))
	var firstSkippedError error
	for _, raw := range rawProfiles {
		entry, ok := raw.(map[string]any)
		if !ok {
			hasUnnamedSkipped = true
			if firstSkippedError == nil {
				firstSkippedError = fmt.Errorf("proxy entry is not an object")
			}
			continue
		}
		profile, err := clashProfile(entry, fingerprint)
		if err != nil {
			if name := strings.TrimSpace(anyString(entry["name"])); name != "" {
				skippedNames = append(skippedNames, name)
			} else {
				hasUnnamedSkipped = true
			}
			if firstSkippedError == nil {
				firstSkippedError = err
			}
			continue
		}
		profiles = append(profiles, profile)
	}
	if len(profiles) == 0 {
		if firstSkippedError != nil {
			return nil, true, nil, false, fmt.Errorf(
				"Clash subscription contains no supported profiles: %w",
				firstSkippedError,
			)
		}
		return nil, true, nil, false, fmt.Errorf("Clash subscription contains no supported profiles")
	}
	return profiles, true, skippedNames, hasUnnamedSkipped, nil
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
		profile["alpn"] = anyStringList(entry["alpn"])
		if typeName == "hysteria" {
			profile["authPayload"] = anyString(entry["auth-str"])
			profile["authPayloadType"] = 1
			profile["obfuscation"] = anyString(entry["obfs"])
			profile["streamReceiveWindow"] = anyInt(entry["recv-window-conn"], 0)
			profile["connectionReceiveWindow"] = anyInt(entry["recv-window"], 0)
			profile["disableMtuDiscovery"] = anyBool(entry["disable-mtu-discovery"])
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
	profile["sni"] = defaultString(anyString(entry["servername"]), anyString(entry["sni"]))
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
		if anyBool(ws["v2ray-http-upgrade"]) {
			profile["type"] = "httpupgrade"
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

const (
	maxJSONProfileDepth  = 64
	maxJSONProfileTokens = 2_000_000
)

type jsonStructureFrame struct {
	kind      json.Delim
	entries   int
	expectKey bool
}

// validateBoundedJSONStructure scans JSON before YAML or generic map decoding can materialize it.
// It bounds every array/object, total token work, and nesting depth, including objects embedded
// inside a single outbound. Non-JSON input is left to the existing YAML/link parsers.
func validateBoundedJSONStructure(input []byte) (bool, error) {
	trimmed := bytes.TrimSpace(input)
	if len(trimmed) == 0 || trimmed[0] != '{' && trimmed[0] != '[' {
		return false, nil
	}
	decoder := json.NewDecoder(bytes.NewReader(trimmed))
	decoder.UseNumber()
	frames := make([]jsonStructureFrame, 0, 8)
	tokens := 0
	rootSeen := false

	consumeParentValue := func() error {
		if len(frames) == 0 {
			if rootSeen {
				return fmt.Errorf("JSON profile document contains multiple root values")
			}
			rootSeen = true
			return nil
		}
		parent := &frames[len(frames)-1]
		if parent.kind == '[' {
			parent.entries++
			if parent.entries > maxProfileLinkCount {
				return fmt.Errorf("JSON profile document contains too many collection entries")
			}
			return nil
		}
		if parent.expectKey {
			return fmt.Errorf("JSON profile document has an invalid object")
		}
		parent.expectKey = true
		return nil
	}

	for {
		token, err := decoder.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return false, nil
		}
		tokens++
		if tokens > maxJSONProfileTokens {
			return true, fmt.Errorf("JSON profile document is too complex")
		}

		if delimiter, ok := token.(json.Delim); ok {
			switch delimiter {
			case '{', '[':
				if err := consumeParentValue(); err != nil {
					return true, err
				}
				if len(frames) >= maxJSONProfileDepth {
					return true, fmt.Errorf("JSON profile document is nested too deeply")
				}
				frames = append(frames, jsonStructureFrame{
					kind: delimiter, expectKey: delimiter == '{',
				})
			case '}', ']':
				if len(frames) == 0 ||
					(delimiter == '}' && frames[len(frames)-1].kind != '{') ||
					(delimiter == ']' && frames[len(frames)-1].kind != '[') {
					return false, nil
				}
				frame := frames[len(frames)-1]
				if frame.kind == '{' && !frame.expectKey {
					return false, nil
				}
				frames = frames[:len(frames)-1]
			}
			continue
		}

		if len(frames) > 0 && frames[len(frames)-1].kind == '{' &&
			frames[len(frames)-1].expectKey {
			if _, ok := token.(string); !ok {
				return false, nil
			}
			frame := &frames[len(frames)-1]
			frame.entries++
			if frame.entries > maxProfileLinkCount {
				return true, fmt.Errorf("JSON profile document contains too many object fields")
			}
			frame.expectKey = false
			continue
		}
		if err := consumeParentValue(); err != nil {
			return true, err
		}
	}
	if len(frames) != 0 || !rootSeen {
		return false, nil
	}
	return true, nil
}

func parseJSONDocument(input string) ([]map[string]any, bool, error) {
	return parseJSONDocumentBytes([]byte(input), 0, true)
}

func parseJSONDocumentBytes(encoded []byte, depth int, validate bool) ([]map[string]any, bool, error) {
	if depth > maxJSONProfileDepth {
		return nil, true, fmt.Errorf("JSON profile document is nested too deeply")
	}
	trimmed := bytes.TrimSpace(encoded)
	if len(trimmed) == 0 || validate && !json.Valid(trimmed) {
		return nil, false, nil
	}
	if trimmed[0] == '[' {
		return parseJSONArrayDocument(trimmed, depth)
	}
	if trimmed[0] != '{' {
		return nil, false, nil
	}

	var rawObject map[string]json.RawMessage
	if err := json.Unmarshal(trimmed, &rawObject); err != nil {
		return nil, false, nil
	}
	if rawOutbounds, exists := rawObject["outbounds"]; exists {
		outbounds := bytes.TrimSpace(rawOutbounds)
		if len(outbounds) > 0 && outbounds[0] == '[' {
			return parseJSONOutbounds(outbounds)
		}
	}

	object, ok := decodeJSONObject(trimmed)
	if !ok {
		return nil, false, nil
	}
	if object["method"] != nil && object["server"] != nil {
		plugin := anyString(object["plugin"])
		if opts := anyString(object["plugin_opts"]); plugin != "" && opts != "" {
			plugin += ";" + opts
		}
		return []map[string]any{{
			"kind": "ss", "serverAddress": anyString(object["server"]), "serverPort": anyInt(object["server_port"], 8388),
			"method": anyString(object["method"]), "password": anyString(object["password"]), "plugin": plugin, "name": anyString(object["remarks"]),
		}}, true, nil
	}
	// A standalone sing-box outbound is only safe to preserve as a custom
	// outbound when it declares its type.  SIP008 Shadowsocks documents also
	// use server/server_port and must be handled by the branch above instead.
	if anyString(object["type"]) != "" && anyString(object["server"]) != "" && object["server_port"] != nil {
		encoded, _ := json.Marshal(object)
		return []map[string]any{{"kind": "config", "type": 1, "config": string(encoded)}}, true, nil
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
		return []map[string]any{profile}, profile["serverAddress"] != "", nil
	}
	if profile, ok := hysteriaObject(object); ok {
		return []map[string]any{profile}, true, nil
	}
	return nil, false, nil
}

func parseJSONArrayDocument(encoded []byte, depth int) ([]map[string]any, bool, error) {
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.UseNumber()
	if token, err := decoder.Token(); err != nil || token != json.Delim('[') {
		return nil, false, nil
	}
	profiles := make([]map[string]any, 0)
	entries := 0
	for decoder.More() {
		entries++
		if entries > maxProfileLinkCount {
			return nil, true, fmt.Errorf("JSON profile document contains too many entries")
		}
		var raw json.RawMessage
		if err := decoder.Decode(&raw); err != nil {
			return nil, false, nil
		}
		parsed, parsedOK, err := parseJSONDocumentBytes(raw, depth+1, false)
		if err != nil {
			return nil, true, err
		}
		if parsedOK {
			if len(parsed) > maxProfileLinkCount-len(profiles) {
				return nil, true, fmt.Errorf("JSON profile document contains too many profiles")
			}
			profiles = append(profiles, parsed...)
		}
	}
	if _, err := decoder.Token(); err != nil || !decoderAtEOF(decoder) {
		return nil, false, nil
	}
	return profiles, len(profiles) > 0, nil
}

func parseJSONOutbounds(encoded []byte) ([]map[string]any, bool, error) {
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.UseNumber()
	if token, err := decoder.Token(); err != nil || token != json.Delim('[') {
		return nil, false, nil
	}
	profiles := make([]map[string]any, 0)
	entries := 0
	for decoder.More() {
		entries++
		if entries > maxProfileLinkCount {
			return nil, true, fmt.Errorf("sing-box document contains too many outbounds")
		}
		var raw json.RawMessage
		if err := decoder.Decode(&raw); err != nil {
			return nil, false, nil
		}
		outbound, ok := decodeJSONObject(raw)
		if !ok {
			continue
		}
		typeName := anyString(outbound["type"])
		if typeName == "" || typeName == "direct" || typeName == "block" || typeName == "dns" || typeName == "selector" || typeName == "urltest" {
			continue
		}
		canonical, _ := json.Marshal(outbound)
		profiles = append(profiles, map[string]any{
			"kind": "config", "name": anyString(outbound["tag"]),
			"type": 1, "config": string(canonical),
		})
	}
	if _, err := decoder.Token(); err != nil || !decoderAtEOF(decoder) {
		return nil, false, nil
	}
	return profiles, len(profiles) > 0, nil
}

func decodeJSONObject(encoded []byte) (map[string]any, bool) {
	var object map[string]any
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.UseNumber()
	if decoder.Decode(&object) != nil || !decoderAtEOF(decoder) {
		return nil, false
	}
	return object, object != nil
}

func decoderAtEOF(decoder *json.Decoder) bool {
	var extra any
	return decoder.Decode(&extra) == io.EOF
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
	if len(profiles) > maxProfileLinkCount {
		return "", fmt.Errorf("too many profiles")
	}
	encoded, err := json.Marshal(profiles)
	return string(encoded), err
}

func clashSSPlugin(name string, options map[string]any) string {
	switch name {
	case "obfs", "simple-obfs", "obfs-local":
		parts := []string{"obfs-local"}
		if mode := anyString(options["mode"]); mode != "" {
			parts = append(parts, "obfs="+mode)
		}
		if host := anyString(options["host"]); host != "" {
			parts = append(parts, "obfs-host="+host)
		}
		return strings.Join(parts, ";")
	case "v2ray-plugin":
		parts := []string{name}
		if mode := anyString(options["mode"]); mode != "" {
			parts = append(parts, "mode="+mode)
		}
		if anyBool(options["tls"]) {
			parts = append(parts, "tls")
		}
		for _, key := range []string{"host", "path"} {
			if value := anyString(options[key]); value != "" {
				parts = append(parts, key+"="+value)
			}
		}
		if anyBool(options["mux"]) {
			parts = append(parts, "mux=8")
		}
		return strings.Join(parts, ";")
	default:
		return name
	}
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
