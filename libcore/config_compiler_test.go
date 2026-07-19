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
			"tunImplementation": 0, "mtu": 1500, "ipv6Mode": 0, "trafficSniffing": 1,
			"resolveDestination": true, "remoteDns": "https://dns.google/dns-query",
			"directDns": "https://223.5.5.5/dns-query", "enableDnsRouting": true,
			"enableFakeDns": true, "bypassLanInCore": true, "serverDomainStrategy": "prefer_ipv4",
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
	rules := config["route"].(map[string]any)["rules"].([]any)
	if len(rules) < 4 {
		t.Fatalf("missing built-in/user rules: %#v", rules)
	}
	serialized, _ := json.Marshal(rules)
	if strings.Contains(string(serialized), "sniff_override_destination") || strings.Contains(string(serialized), "domain_strategy") {
		t.Fatalf("legacy inbound fields leaked into route rules: %s", serialized)
	}
}

func TestCompileClientConfigRejectsCircularChain(t *testing.T) {
	request := `{
      "selectedId":1,"forTest":true,
      "settings":{"directDns":"local"},
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

func TestCompileClientConfigRacesConfiguredDNSAndBuildsTestSelector(t *testing.T) {
	request := map[string]any{
		"selectedId": 1,
		"testIds":    []int64{1, 2},
		"forTest":    true,
		"settings": map[string]any{
			"directDns": "https://dns.alidns.com/dns-query\nhttps://doh.pub/dns-query",
		},
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
