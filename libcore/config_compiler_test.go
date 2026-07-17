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

func TestMergeConfigMapPreservesListOperators(t *testing.T) {
	destination := map[string]any{"rules": []any{"middle"}}
	mergeConfigMap(destination, map[string]any{"+rules": []any{"first"}, "rules+": []any{"last"}})
	rules := destination["rules"].([]any)
	if len(rules) != 3 || rules[0] != "first" || rules[2] != "last" {
		t.Fatalf("unexpected merge result: %#v", rules)
	}
}
