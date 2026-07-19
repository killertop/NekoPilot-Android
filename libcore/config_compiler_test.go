package libcore

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestCompileClientConfigOwnsRuntimeSchema(t *testing.T) {
	request := map[string]any{
		"selectedId": 1,
		"forTest":    false,
		"forExport":  true,
		"settings": map[string]any{
			"isVpn": true, "mixedPort": 2080, "mixedUsername": "user", "mixedPassword": "secret",
			"tunImplementation": 0,
			// Old client values must not override the automatic DNS policy.
			"remoteDns": "https://example.invalid/dns-query", "directDns": "https://example.invalid/dns-query",
			"enableDnsRouting": false, "enableFakeDns": false, "serverDomainStrategy": "ipv6_only",
		},
		"profiles": []any{map[string]any{
			"id": 1, "groupId": 1, "kind": "vless", "external": false, "canMapping": true,
			"bean": map[string]any{
				"serverAddress": "example.com", "serverPort": 443, "uuid": "11111111-1111-1111-1111-111111111111",
				"alterId": -1, "security": "tls", "sni": "example.com", "type": "tcp",
			},
		}},
		"groups": []any{map[string]any{"id": 1, "frontProxy": -1, "landingProxy": -1}},
		"rules": []any{map[string]any{
			"id": 1, "name": "China", "domains": "geosite:cn", "ip": "geoip:cn", "outbound": -1,
		}},
	}
	encoded, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	resultJSON, err := CompileClientConfig(string(encoded))
	if err != nil {
		t.Fatal(err)
	}
	var result clientConfigResult
	if err = json.Unmarshal([]byte(resultJSON), &result); err != nil {
		t.Fatal(err)
	}
	if result.Config == "" {
		t.Fatal("empty compiled config")
	}
	var config map[string]any
	if err = json.Unmarshal([]byte(result.Config), &config); err != nil {
		t.Fatal(err)
	}
	outbounds := config["outbounds"].([]any)
	if outbounds[0].(map[string]any)["type"] != "vless" {
		t.Fatalf("unexpected first outbound: %#v", outbounds[0])
	}
	inbounds := config["inbounds"].([]any)
	if tun := inbounds[0].(map[string]any); tun["mtu"] != float64(configTunMTU) {
		t.Fatalf("unexpected automatic TUN MTU: %#v", tun)
	}
	if addresses := inbounds[0].(map[string]any)["address"].([]any); len(addresses) != 2 {
		t.Fatalf("automatic IPv6 route is missing: %#v", inbounds[0])
	}
	rules := config["route"].(map[string]any)["rules"].([]any)
	if len(rules) < 4 {
		t.Fatalf("missing built-in/user rules: %#v", rules)
	}
	serialized, _ := json.Marshal(rules)
	if strings.Contains(string(serialized), "sniff_override_destination") || strings.Contains(string(serialized), "domain_strategy") {
		t.Fatalf("legacy inbound fields leaked into route rules: %s", serialized)
	}
	var hasSniff, hasPrivateBypass bool
	for _, value := range rules {
		rule := value.(map[string]any)
		if rule["action"] == "resolve" {
			t.Fatalf("destination resolution must remain disabled: %#v", rule)
		}
		if rule["action"] == "sniff" {
			hasSniff = true
		}
		if rule["ip_is_private"] == true && rule["outbound"] == configTagBypass {
			hasPrivateBypass = true
		}
	}
	if !hasSniff || !hasPrivateBypass {
		t.Fatalf("missing fixed route defaults: %#v", rules)
	}
	dnsConfig := config["dns"].(map[string]any)
	dnsServers := map[string]bool{}
	for _, value := range dnsConfig["servers"].([]any) {
		dnsServers[value.(map[string]any)["tag"].(string)] = true
	}
	for _, tag := range []string{"dns-direct", "dns-remote", "dns-fake"} {
		if !dnsServers[tag] {
			t.Fatalf("missing automatic DNS server %q: %#v", tag, dnsServers)
		}
	}
	serializedDNS, _ := json.Marshal(dnsConfig)
	if strings.Contains(string(serializedDNS), "example.invalid") {
		t.Fatalf("legacy DNS preference leaked into automatic DNS config: %s", serializedDNS)
	}
	for _, endpoint := range []string{
		"https://dns.google/dns-query",
		"https://cloudflare-dns.com/dns-query",
		"https://dns.alidns.com/dns-query",
		"https://doh.pub/dns-query",
	} {
		if !strings.Contains(string(serializedDNS), endpoint) {
			t.Fatalf("automatic DNS fallback %q is missing: %s", endpoint, serializedDNS)
		}
	}
}

func TestCompileClientConfigRejectsCircularChain(t *testing.T) {
	request := `{
      "selectedId":1,"forTest":true,
      "settings":{},
      "profiles":[
        {"id":1,"groupId":1,"kind":"chain","chain":[2],"bean":{}},
        {"id":2,"groupId":1,"kind":"chain","chain":[1],"bean":{}}
      ],
      "groups":[{"id":1,"frontProxy":-1,"landingProxy":-1}]
    }`
	if _, err := CompileClientConfig(request); err == nil {
		t.Fatal("expected circular chain error")
	}
}

func TestCompileClientConfigUsesAutomaticDNSAndBuildsTestSelector(t *testing.T) {
	request := map[string]any{
		"selectedId": 1,
		"testIds":    []int64{1, 2},
		"forTest":    true,
		"settings":   map[string]any{},
		"profiles": []any{
			map[string]any{
				"id": 1, "groupId": 1, "kind": "vless", "bean": map[string]any{
					"serverAddress": "one.example.com", "serverPort": 443,
					"uuid": "11111111-1111-1111-1111-111111111111", "alterId": -1,
					"security": "tls", "sni": "one.example.com", "type": "tcp",
				},
			},
			map[string]any{
				"id": 2, "groupId": 1, "kind": "vless", "bean": map[string]any{
					"serverAddress": "two.example.com", "serverPort": 443,
					"uuid": "22222222-2222-2222-2222-222222222222", "alterId": -1,
					"security": "tls", "sni": "two.example.com", "type": "tcp",
				},
			},
		},
		"groups": []any{map[string]any{"id": 1, "frontProxy": -1, "landingProxy": -1}},
	}
	encoded, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	resultJSON, err := CompileClientConfig(string(encoded))
	if err != nil {
		t.Fatal(err)
	}
	var result clientConfigResult
	if err = json.Unmarshal([]byte(resultJSON), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.TestOutbounds) != 2 {
		t.Fatalf("expected two test outbounds, got %#v", result.TestOutbounds)
	}
	var config map[string]any
	if err = json.Unmarshal([]byte(result.Config), &config); err != nil {
		t.Fatal(err)
	}
	if err = ValidateSingBoxConfig(result.Config); err != nil {
		t.Fatalf("compiled DNS race config is not accepted by sing-box: %v", err)
	}

	dnsConfig := config["dns"].(map[string]any)
	servers := dnsConfig["servers"].([]any)
	var raced map[string]any
	for _, value := range servers {
		server := value.(map[string]any)
		if server["tag"] == "dns-direct" && server["type"] == dnsRaceType {
			raced = server
			break
		}
	}
	if raced == nil {
		t.Fatalf("missing %s DNS transport: %#v", dnsRaceType, servers)
	}
	if children := raced["servers"].([]any); len(children) != 2 {
		t.Fatalf("expected two DNS race children, got %#v", children)
	}

	var selector map[string]any
	for _, value := range config["outbounds"].([]any) {
		outbound := value.(map[string]any)
		if outbound["tag"] == configTagProxy {
			selector = outbound
			break
		}
	}
	if selector == nil || len(selector["outbounds"].([]any)) != 2 {
		t.Fatalf("test selector does not contain both profiles: %#v", selector)
	}
}

func TestMergeConfigMapPreservesListOperators(t *testing.T) {
	destination := map[string]any{"rules": []any{"middle"}}
	mergeConfigMap(destination, map[string]any{"+rules": []any{"first"}, "rules+": []any{"last"}})
	rules := destination["rules"].([]any)
	if len(rules) != 3 || rules[0] != "first" || rules[2] != "last" {
		t.Fatalf("unexpected merge result: %#v", rules)
	}
}
