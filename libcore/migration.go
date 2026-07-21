package libcore

import (
	"bytes"
	"compress/zlib"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/url"
	"strconv"
	"strings"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"gopkg.in/yaml.v3"
)

const (
	maxPortableConfigBytes = 1_000_000
	maxPortableConfigLines = 20_000
	maxPortableSections    = 256
)

type rulePortsResult struct {
	Ports  []int    `json:"ports"`
	Ranges []string `json:"ranges"`
}

// NormalizeRulePorts moves untrusted route-port parsing out of the Android UI
// process. Invalid values are ignored for compatibility with the old parser.
func NormalizeRulePorts(input string) (string, error) {
	result := rulePortsResult{Ports: []int{}, Ranges: []string{}}
	seenPorts := make(map[int]bool)
	seenRanges := make(map[string]bool)
	for _, token := range splitList(input) {
		token = strings.TrimSpace(token)
		if strings.Contains(token, ":") {
			bounds := strings.SplitN(token, ":", 2)
			start, startErr := strconv.Atoi(strings.TrimSpace(bounds[0]))
			end, endErr := strconv.Atoi(strings.TrimSpace(bounds[1]))
			if startErr == nil && endErr == nil && start >= 1 && end >= start && end <= 65535 {
				normalized := fmt.Sprintf("%d:%d", start, end)
				if !seenRanges[normalized] {
					seenRanges[normalized] = true
					result.Ranges = append(result.Ranges, normalized)
				}
			}
			continue
		}
		port, err := strconv.Atoi(token)
		if err == nil && port >= 1 && port <= 65535 && !seenPorts[port] {
			seenPorts[port] = true
			result.Ports = append(result.Ports, port)
		}
	}
	encoded, err := json.Marshal(result)
	return string(encoded), err
}

func splitList(input string) []string {
	return strings.FieldsFunc(input, func(r rune) bool {
		return r == ',' || r == '\n' || r == '\r'
	})
}

type wireGuardProfile struct {
	LocalAddress     string `json:"localAddress"`
	PrivateKey       string `json:"privateKey"`
	ServerAddress    string `json:"serverAddress"`
	ServerPort       int    `json:"serverPort"`
	PeerPublicKey    string `json:"peerPublicKey"`
	PeerPreSharedKey string `json:"peerPreSharedKey"`
	MTU              int    `json:"mtu"`
}

type iniSection map[string][]string

// ParseWireGuardConfig parses the portable WireGuard INI format and returns
// Android-neutral DTOs. Android remains responsible only for bean persistence.
func ParseWireGuardConfig(input string) (string, error) {
	sections, err := parseINI(input)
	if err != nil {
		return "", err
	}
	interfaces := sections["Interface"]
	if len(interfaces) != 1 {
		return "", fmt.Errorf("missing or repeated Interface section")
	}
	iface := interfaces[0]
	addresses := iniValues(iface, "Address")
	if len(addresses) == 0 {
		return "", fmt.Errorf("empty address in Interface section")
	}
	var flattened []string
	for _, address := range addresses {
		for _, item := range strings.Split(address, ",") {
			if item = strings.TrimSpace(item); item != "" {
				flattened = append(flattened, item)
			}
		}
	}
	if len(flattened) == 0 {
		return "", fmt.Errorf("empty address in Interface section")
	}
	mtu := 1420
	if value := iniValue(iface, "MTU"); value != "" {
		if parsed, parseErr := strconv.Atoi(value); parseErr == nil && parsed > 0 {
			mtu = parsed
		}
	}
	peers := sections["Peer"]
	if len(peers) == 0 {
		return "", fmt.Errorf("missing Peer sections")
	}
	profiles := make([]wireGuardProfile, 0, len(peers))
	for _, peer := range peers {
		host, port, splitErr := splitEndpoint(iniValue(peer, "Endpoint"))
		publicKey := iniValue(peer, "PublicKey")
		if splitErr != nil || publicKey == "" {
			continue
		}
		profiles = append(profiles, wireGuardProfile{
			LocalAddress: strings.Join(flattened, "\n"), PrivateKey: iniValue(iface, "PrivateKey"),
			ServerAddress: host, ServerPort: port, PeerPublicKey: publicKey,
			PeerPreSharedKey: iniValue(peer, "PresharedKey"), MTU: mtu,
		})
	}
	if len(profiles) == 0 {
		return "", fmt.Errorf("empty available peer list")
	}
	encoded, err := json.Marshal(profiles)
	return string(encoded), err
}

func splitEndpoint(endpoint string) (string, int, error) {
	endpoint = strings.TrimSpace(endpoint)
	if endpoint == "" {
		return "", 0, fmt.Errorf("empty endpoint")
	}
	host, portText, err := net.SplitHostPort(endpoint)
	if err != nil {
		index := strings.LastIndexByte(endpoint, ':')
		if index <= 0 || strings.Contains(endpoint[:index], ":") {
			return "", 0, fmt.Errorf("invalid endpoint %q", endpoint)
		}
		host, portText = endpoint[:index], endpoint[index+1:]
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 || strings.TrimSpace(host) == "" {
		return "", 0, fmt.Errorf("invalid endpoint %q", endpoint)
	}
	return strings.Trim(host, "[]"), port, nil
}

func parseINI(input string) (map[string][]iniSection, error) {
	if len(input) > maxPortableConfigBytes {
		return nil, fmt.Errorf("INI input is too large")
	}
	result := make(map[string][]iniSection)
	var current iniSection
	lineCount, sectionCount := 0, 0
	for _, raw := range strings.Split(input, "\n") {
		lineCount++
		if lineCount > maxPortableConfigLines {
			return nil, fmt.Errorf("INI input has too many lines")
		}
		line := strings.TrimSpace(strings.TrimSuffix(raw, "\r"))
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			name := strings.TrimSpace(line[1 : len(line)-1])
			if name == "" {
				return nil, fmt.Errorf("INI section name is empty")
			}
			sectionCount++
			if sectionCount > maxPortableSections {
				return nil, fmt.Errorf("INI input has too many sections")
			}
			current = make(iniSection)
			result[name] = append(result[name], current)
			continue
		}
		if current == nil {
			return nil, fmt.Errorf("INI property appears before a section")
		}
		separator := strings.IndexByte(line, '=')
		if separator <= 0 {
			return nil, fmt.Errorf("invalid INI property")
		}
		key := strings.TrimSpace(line[:separator])
		value := strings.TrimSpace(line[separator+1:])
		if key == "" || len(key) > 128 || len(value) > 65_536 {
			return nil, fmt.Errorf("invalid INI property")
		}
		current[key] = append(current[key], value)
	}
	return result, nil
}

func iniValues(section iniSection, key string) []string { return section[key] }
func iniValue(section iniSection, key string) string {
	values := section[key]
	if len(values) == 0 {
		return ""
	}
	return values[len(values)-1]
}

// YAMLToJSON provides a bounded bridge for portable profiles such as Hysteria.
func YAMLToJSON(input string) (string, error) {
	if len(input) > maxPortableConfigBytes {
		return "", fmt.Errorf("YAML input is too large")
	}
	var value any
	decoder := yaml.NewDecoder(strings.NewReader(input))
	if err := decoder.Decode(&value); err != nil {
		return "", err
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		if err == nil {
			return "", fmt.Errorf("multiple YAML documents are unsupported")
		}
		return "", err
	}
	encoded, err := json.Marshal(value)
	return string(encoded), err
}

type hysteriaLink struct {
	ProtocolVersion int    `json:"protocolVersion"`
	ServerAddress   string `json:"serverAddress"`
	ServerPorts     string `json:"serverPorts"`
	Name            string `json:"name,omitempty"`
	AuthPayloadType int    `json:"authPayloadType,omitempty"`
	AuthPayload     string `json:"authPayload,omitempty"`
	SNI             string `json:"sni,omitempty"`
	AllowInsecure   bool   `json:"allowInsecure,omitempty"`
	UploadMbps      *int   `json:"uploadMbps,omitempty"`
	DownloadMbps    *int   `json:"downloadMbps,omitempty"`
	ALPN            string `json:"alpn,omitempty"`
	Obfuscation     string `json:"obfuscation,omitempty"`
	Protocol        int    `json:"protocol,omitempty"`
}

// ParseHysteriaLink parses both legacy hysteria:// and hy2/hysteria2 links.
func ParseHysteriaLink(input string) (string, error) {
	link, err := url.Parse(strings.TrimSpace(input))
	if err != nil || link.Host == "" {
		return "", fmt.Errorf("invalid Hysteria link")
	}
	version := 0
	switch strings.ToLower(link.Scheme) {
	case "hysteria":
		version = 1
	case "hy2", "hysteria2":
		version = 2
	default:
		return "", fmt.Errorf("unsupported Hysteria scheme")
	}
	host := link.Hostname()
	if host == "" {
		return "", fmt.Errorf("missing Hysteria server")
	}
	port := link.Port()
	if port == "" {
		port = "443"
	}
	if parsed, parseErr := strconv.Atoi(port); parseErr != nil || parsed < 1 || parsed > 65535 {
		return "", fmt.Errorf("invalid Hysteria port")
	}
	query := link.Query()
	result := hysteriaLink{
		ProtocolVersion: version, ServerAddress: host, ServerPorts: port, Name: link.Fragment,
	}
	if multiPort := query.Get("mport"); multiPort != "" {
		result.ServerPorts = multiPort
	}
	insecure := query.Get("insecure")
	result.AllowInsecure = insecure == "1" || strings.EqualFold(insecure, "true")
	if version == 1 {
		result.SNI = query.Get("peer")
		if auth := query.Get("auth"); auth != "" {
			result.AuthPayloadType, result.AuthPayload = 1, auth
		}
		result.UploadMbps = optionalNonNegativeInt(query.Get("upmbps"))
		result.DownloadMbps = optionalNonNegativeInt(query.Get("downmbps"))
		result.ALPN = query.Get("alpn")
		result.Obfuscation = query.Get("obfsParam")
		switch query.Get("protocol") {
		case "faketcp":
			result.Protocol = 1
		case "wechat-video":
			result.Protocol = 2
		}
	} else {
		result.SNI = query.Get("sni")
		result.Obfuscation = query.Get("obfs-password")
		if link.User != nil {
			username := link.User.Username()
			if password, exists := link.User.Password(); exists && password != "" {
				result.AuthPayload = username + ":" + password
			} else {
				result.AuthPayload = username
			}
		}
	}
	encoded, err := json.Marshal(result)
	return string(encoded), err
}

func optionalNonNegativeInt(value string) *int {
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed < 0 {
		return nil
	}
	return &parsed
}

// ValidateSingBoxConfig makes the Go/sing-box option model the authoritative
// compiler check before generated config reaches the Android service lifecycle.
func ValidateSingBoxConfig(config string) error {
	ctx := context.Background()
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(config)); err != nil {
		return fmt.Errorf("decode config: %w", err)
	}
	return nil
}

// ZlibCompress keeps the historical universal-link wire format while moving
// its codec implementation beside the rest of the portable profile pipeline.
func ZlibCompress(input []byte, level int32) ([]byte, error) {
	var output bytes.Buffer
	writer, err := zlib.NewWriterLevel(&output, int(level))
	if err != nil {
		return nil, err
	}
	if _, err = writer.Write(input); err != nil {
		_ = writer.Close()
		return nil, err
	}
	if err = writer.Close(); err != nil {
		return nil, err
	}
	return output.Bytes(), nil
}

// ZlibDecompress rejects truncation, trailing bytes and decompression bombs.
func ZlibDecompress(input []byte, maxOutputBytes int64) ([]byte, error) {
	if maxOutputBytes <= 0 {
		return nil, fmt.Errorf("invalid decompression limit")
	}
	source := bytes.NewReader(input)
	reader, err := zlib.NewReader(source)
	if err != nil {
		return nil, err
	}
	output, readErr := io.ReadAll(io.LimitReader(reader, maxOutputBytes+1))
	closeErr := reader.Close()
	if readErr != nil {
		return nil, readErr
	}
	if closeErr != nil {
		return nil, closeErr
	}
	if int64(len(output)) > maxOutputBytes {
		return nil, fmt.Errorf("decompressed profile is too large")
	}
	if source.Len() != 0 {
		return nil, fmt.Errorf("trailing data after zlib stream")
	}
	return output, nil
}
