package libcore

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"sort"
	"strconv"
	"strings"
)

const (
	configTagMixed  = "mixed-in"
	configTagProxy  = "proxy"
	configTagDirect = "direct"
	configTagBypass = "bypass"
	configTagBlock  = "block"
	configLocalhost = "127.0.0.1"
	configTunMTU    = 9000
	configIPv6Mode  = 1
)

type clientConfigRequest struct {
	SelectedID int64                 `json:"selectedId"`
	TestIDs    []int64               `json:"testIds,omitempty"`
	ForTest    bool                  `json:"forTest"`
	ForExport  bool                  `json:"forExport"`
	Settings   clientConfigSettings  `json:"settings"`
	Profiles   []clientConfigProfile `json:"profiles"`
	Groups     []clientConfigGroup   `json:"groups"`
	Rules      []clientConfigRule    `json:"rules"`
}

type clientConfigSettings struct {
	IsVPN                bool   `json:"isVpn"`
	AllowAccess          bool   `json:"allowAccess"`
	MixedPort            int    `json:"mixedPort"`
	MixedUsername        string `json:"mixedUsername"`
	MixedPassword        string `json:"mixedPassword"`
	TunImplementation    int    `json:"tunImplementation"`
	RemoteDNS            string `json:"remoteDns"`
	DirectDNS            string `json:"directDns"`
	EnableDNSRouting     bool   `json:"enableDnsRouting"`
	EnableFakeDNS        bool   `json:"enableFakeDns"`
	LogLevel             int    `json:"logLevel"`
	GlobalAllowInsecure  bool   `json:"globalAllowInsecure"`
	ServerDomainStrategy string `json:"serverDomainStrategy"`
	RemoteDNSStrategy    string `json:"remoteDnsStrategy"`
	DirectDNSStrategy    string `json:"directDnsStrategy"`
}

type clientConfigProfile struct {
	ID                  int64           `json:"id"`
	GroupID             int64           `json:"groupId"`
	Kind                string          `json:"kind"`
	Bean                json.RawMessage `json:"bean"`
	Chain               []int64         `json:"chain,omitempty"`
	External            bool            `json:"external"`
	CanMapping          bool            `json:"canMapping"`
	SkipMappingWhenLast bool            `json:"skipMappingWhenLast"`
	Multiplex           map[string]any  `json:"multiplex,omitempty"`
}

type clientConfigGroup struct {
	ID           int64 `json:"id"`
	FrontProxy   int64 `json:"frontProxy"`
	LandingProxy int64 `json:"landingProxy"`
}

type clientConfigRule struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	Config     string `json:"config"`
	Domains    string `json:"domains"`
	IP         string `json:"ip"`
	Port       string `json:"port"`
	SourcePort string `json:"sourcePort"`
	Network    string `json:"network"`
	Source     string `json:"source"`
	Protocol   string `json:"protocol"`
	Outbound   int64  `json:"outbound"`
	UIDs       []int  `json:"uids"`
}

type clientConfigExternal struct {
	Port         int    `json:"port"`
	ProfileID    int64  `json:"profileId"`
	FinalAddress string `json:"finalAddress"`
	FinalPort    int    `json:"finalPort"`
}

type clientConfigResult struct {
	Config         string                   `json:"config"`
	ExternalChains [][]clientConfigExternal `json:"externalChains"`
	TestOutbounds  map[int64]string         `json:"testOutbounds,omitempty"`
	Warnings       []string                 `json:"warnings"`
}

type clientConfigCompiler struct {
	request          clientConfigRequest
	profiles         map[int64]*clientConfigProfile
	profileBeans     map[int64]map[string]any
	groups           map[int64]clientConfigGroup
	resolvedChains   map[int64][]*clientConfigProfile
	resolvingChains  map[int64]bool
	globalOutbounds  map[int64]string
	tagMap           map[int64]string
	bypassDNSHosts   map[string]bool
	directDNSDomains map[string]bool
	outbounds        []any
	inbounds         []any
	routeRules       []any
	ruleSets         []any
	dnsRules         []any
	dnsServers       []any
	externalChains   [][]clientConfigExternal
	warnings         []string
}

// CompileClientConfig makes Go and the linked sing-box option model the sole
// owner of the runtime configuration schema. Android supplies only a portable
// snapshot of persisted profiles, groups, rules and settings.
func CompileClientConfig(requestJSON string) (string, error) {
	var request clientConfigRequest
	decoder := json.NewDecoder(strings.NewReader(requestJSON))
	decoder.UseNumber()
	if err := decoder.Decode(&request); err != nil {
		return "", fmt.Errorf("decode config request: %w", err)
	}
	compiler, err := newClientConfigCompiler(request)
	if err != nil {
		return "", err
	}
	result, err := compiler.compile()
	if err != nil {
		return "", err
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		return "", fmt.Errorf("encode config result: %w", err)
	}
	return string(encoded), nil
}

func newClientConfigCompiler(request clientConfigRequest) (*clientConfigCompiler, error) {
	compiler := &clientConfigCompiler{
		request:          request,
		profiles:         make(map[int64]*clientConfigProfile, len(request.Profiles)),
		profileBeans:     make(map[int64]map[string]any, len(request.Profiles)),
		groups:           make(map[int64]clientConfigGroup, len(request.Groups)),
		resolvedChains:   make(map[int64][]*clientConfigProfile),
		resolvingChains:  make(map[int64]bool),
		globalOutbounds:  make(map[int64]string),
		tagMap:           make(map[int64]string),
		bypassDNSHosts:   make(map[string]bool),
		directDNSDomains: make(map[string]bool),
	}
	for index := range request.Profiles {
		profile := &request.Profiles[index]
		if profile.ID <= 0 {
			return nil, fmt.Errorf("invalid profile id %d", profile.ID)
		}
		if _, exists := compiler.profiles[profile.ID]; exists {
			return nil, fmt.Errorf("duplicate profile id %d", profile.ID)
		}
		var bean map[string]any
		beanDecoder := json.NewDecoder(strings.NewReader(string(profile.Bean)))
		beanDecoder.UseNumber()
		if err := beanDecoder.Decode(&bean); err != nil {
			return nil, fmt.Errorf("decode profile %d: %w", profile.ID, err)
		}
		compiler.profiles[profile.ID] = profile
		compiler.profileBeans[profile.ID] = bean
	}
	for _, group := range request.Groups {
		compiler.groups[group.ID] = group
	}
	if compiler.profiles[request.SelectedID] == nil {
		return nil, fmt.Errorf("selected profile %d does not exist", request.SelectedID)
	}
	return compiler, nil
}

func (c *clientConfigCompiler) compile() (clientConfigResult, error) {
	settings := c.request.Settings
	forTest := c.request.ForTest
	ipv6Mode := configIPv6Mode
	config := map[string]any{
		"log": map[string]any{"level": logLevelName(settings.LogLevel)},
	}

	if !forTest {
		if settings.IsVPN {
			addresses := []string{"172.19.0.1/28"}
			if ipv6Mode == 3 {
				addresses = []string{"fdfe:dcba:9876::1/126"}
			} else if ipv6Mode != 0 {
				addresses = append(addresses, "fdfe:dcba:9876::1/126")
			}
			stack := map[int]string{0: "gvisor", 1: "system", 2: "mixed"}[settings.TunImplementation]
			if stack == "" {
				stack = "mixed"
			}
			c.inbounds = append(c.inbounds, map[string]any{
				"type": "tun", "tag": "tun-in", "stack": stack,
				"mtu": configTunMTU, "address": addresses,
			})
		}
		bind := configLocalhost
		if settings.AllowAccess {
			bind = "0.0.0.0"
		}
		c.inbounds = append(c.inbounds, map[string]any{
			"type": "mixed", "tag": configTagMixed, "listen": bind,
			"listen_port": settings.MixedPort,
			"users":       []any{map[string]any{"username": settings.MixedUsername, "password": settings.MixedPassword}},
		})
		inboundTags := []string{configTagMixed}
		if settings.IsVPN {
			inboundTags = append([]string{"tun-in"}, inboundTags...)
		}
		c.routeRules = append(c.routeRules, map[string]any{"inbound": inboundTags, "action": "sniff"})
	}

	var testOutbounds map[int64]string
	if forTest && len(c.request.TestIDs) > 0 {
		testOutbounds = make(map[int64]string, len(c.request.TestIDs))
		testTags := make([]string, 0, len(c.request.TestIDs))
		seen := make(map[int64]struct{}, len(c.request.TestIDs))
		for _, profileID := range c.request.TestIDs {
			if _, exists := seen[profileID]; exists {
				continue
			}
			seen[profileID] = struct{}{}
			tag, err := c.buildChain(profileID, profileID)
			if err != nil {
				return clientConfigResult{}, err
			}
			testOutbounds[profileID] = tag
			testTags = append(testTags, tag)
		}
		if len(testTags) == 0 {
			return clientConfigResult{}, fmt.Errorf("empty test profile set")
		}
		c.outbounds = append(c.outbounds, map[string]any{
			"type": "selector", "tag": configTagProxy,
			"outbounds": testTags, "default": testTags[0],
		})
	} else if _, err := c.buildChain(0, c.request.SelectedID); err != nil {
		return clientConfigResult{}, err
	}
	for _, rule := range c.request.Rules {
		if rule.Outbound <= 0 || rule.Outbound == c.request.SelectedID {
			continue
		}
		if _, exists := c.tagMap[rule.Outbound]; exists {
			continue
		}
		if c.profiles[rule.Outbound] == nil {
			continue
		}
		tag, err := c.buildChain(rule.Outbound, rule.Outbound)
		if err != nil {
			return clientConfigResult{}, err
		}
		c.tagMap[rule.Outbound] = tag
	}

	if err := c.appendUserRules(ipv6Mode); err != nil {
		return clientConfigResult{}, err
	}
	c.outbounds = append(c.outbounds,
		map[string]any{"type": "direct", "tag": configTagDirect},
		map[string]any{"type": "direct", "tag": configTagBypass},
	)
	if err := c.appendDNS(ipv6Mode); err != nil {
		return clientConfigResult{}, err
	}

	config["inbounds"] = c.inbounds
	config["outbounds"] = c.outbounds
	config["route"] = map[string]any{
		"auto_detect_interface": true,
		"rules":                 c.routeRules,
		"rule_set":              deduplicateRuleSets(c.ruleSets),
	}
	config["dns"] = map[string]any{
		"servers":           c.dnsServers,
		"rules":             c.dnsRules,
		"final":             map[bool]string{true: "dns-direct", false: "dns-remote"}[forTest],
		"independent_cache": true,
	}
	if !forTest && settings.EnableFakeDNS {
		dns := config["dns"].(map[string]any)
		dns["fakeip"] = map[string]any{
			"enabled": true, "inet4_range": "198.18.0.0/15", "inet6_range": "fc00::/18",
		}
	}
	selectedBean := c.profileBeans[c.request.SelectedID]
	if err := mergeJSONMap(config, anyString(selectedBean["customConfigJson"])); err != nil {
		return clientConfigResult{}, fmt.Errorf("merge profile custom config: %w", err)
	}
	encodedConfig, err := json.Marshal(config)
	if err != nil {
		return clientConfigResult{}, fmt.Errorf("encode sing-box config: %w", err)
	}
	if c.request.ForExport {
		if err := ValidateSingBoxConfig(string(encodedConfig)); err != nil {
			return clientConfigResult{}, err
		}
	}
	return clientConfigResult{
		Config: string(encodedConfig), ExternalChains: c.externalChains,
		TestOutbounds: testOutbounds, Warnings: c.warnings,
	}, nil
}

func (c *clientConfigCompiler) resolveChain(profileID int64) ([]*clientConfigProfile, error) {
	if cached, exists := c.resolvedChains[profileID]; exists {
		return append([]*clientConfigProfile(nil), cached...), nil
	}
	profile := c.profiles[profileID]
	if profile == nil {
		return nil, fmt.Errorf("profile %d does not exist", profileID)
	}
	if c.resolvingChains[profileID] {
		return nil, fmt.Errorf("circular proxy chain at %d", profileID)
	}
	c.resolvingChains[profileID] = true
	defer delete(c.resolvingChains, profileID)
	var result []*clientConfigProfile
	if len(profile.Chain) == 0 {
		result = []*clientConfigProfile{profile}
	} else {
		for _, childID := range profile.Chain {
			child, err := c.resolveChain(childID)
			if err != nil {
				return nil, err
			}
			result = append(result, child...)
		}
		reverseProfiles(result)
	}
	c.resolvedChains[profileID] = append([]*clientConfigProfile(nil), result...)
	return result, nil
}

func (c *clientConfigCompiler) resolveChainWithGroup(profileID int64) ([]*clientConfigProfile, error) {
	profile := c.profiles[profileID]
	result, err := c.resolveChain(profileID)
	if err != nil {
		return nil, err
	}
	result = append([]*clientConfigProfile(nil), result...)
	group, exists := c.groups[profile.GroupID]
	if !exists {
		return result, nil
	}
	if group.FrontProxy > 0 {
		if front := c.profiles[group.FrontProxy]; front != nil {
			result = append(result, front)
		}
	}
	if group.LandingProxy > 0 {
		if landing := c.profiles[group.LandingProxy]; landing != nil {
			result = append([]*clientConfigProfile{landing}, result...)
		}
	}
	return result, nil
}

func (c *clientConfigCompiler) buildChain(chainID, profileID int64) (string, error) {
	profiles, err := c.resolveChainWithGroup(profileID)
	if err != nil {
		return "", err
	}
	if len(profiles) == 0 {
		return "", fmt.Errorf("empty proxy chain %d", profileID)
	}
	chainTag := fmt.Sprintf("c-%d", chainID)
	chainOutboundTag := ""
	externalChain := []clientConfigExternal{}
	var pastOutbound map[string]any
	var pastProfile *clientConfigProfile
	var pastInboundTag string
	muxApplied := false

	for index, profile := range profiles {
		bean := c.profileBeans[profile.ID]
		tag := fmt.Sprintf("%s-%d", chainTag, profile.ID)
		needGlobal := index == len(profiles)-1
		if needGlobal {
			tag = fmt.Sprintf("g-%d", profile.ID)
			if host := profileServer(bean); host != "" {
				c.bypassDNSHosts[host] = true
			}
		}
		if chainID == 0 && index == 0 {
			tag = configTagProxy
		}
		if needGlobal {
			if existing, exists := c.globalOutbounds[profile.ID]; exists {
				if index == 0 {
					chainOutboundTag = existing
				}
				continue
			}
			c.globalOutbounds[profile.ID] = tag
		}
		if index > 0 {
			if pastProfile.External {
				c.routeRules = append(c.routeRules, map[string]any{"inbound": []string{pastInboundTag}, "outbound": tag})
			} else if pastOutbound != nil {
				pastOutbound["detour"] = tag
			}
		} else {
			chainOutboundTag = tag
		}

		var outbound map[string]any
		var external *clientConfigExternal
		if profile.External {
			port, err := reserveLocalPort()
			if err != nil {
				return "", err
			}
			outbound = map[string]any{"type": "socks", "server": configLocalhost, "server_port": port}
			externalChain = append(externalChain, clientConfigExternal{
				Port: port, ProfileID: profile.ID,
				FinalAddress: anyString(bean["serverAddress"]), FinalPort: anyInt(bean["serverPort"], 0),
			})
			external = &externalChain[len(externalChain)-1]
		} else if profile.Kind == "config" {
			if err := decodeMap(anyString(bean["config"]), &outbound); err != nil {
				return "", fmt.Errorf("decode custom outbound %d: %w", profile.ID, err)
			}
		} else {
			outbound, err = buildProfileOutboundMap(profile.Kind, bean, c.request.Settings.GlobalAllowInsecure)
			if err != nil {
				return "", fmt.Errorf("build profile %d: %w", profile.ID, err)
			}
			if !muxApplied && anyBool(profile.Multiplex["enabled"]) {
				muxApplied = true
				outbound["multiplex"] = cloneMap(profile.Multiplex)
			}
		}
		if anyBool(bean["sUoT"]) {
			outbound["udp_over_tcp"] = true
		}
		if pastProfile != nil && c.request.Settings.ServerDomainStrategy != "" {
			previousHost := profileServer(c.profileBeans[pastProfile.ID])
			if previousHost != "" && net.ParseIP(previousHost) == nil {
				c.directDNSDomains["full:"+previousHost] = true
			}
		}
		outbound["domain_strategy"] = map[bool]string{true: "", false: c.request.Settings.ServerDomainStrategy}[c.request.ForTest]
		outbound["tag"] = tag
		if err := mergeJSONMap(outbound, anyString(bean["customOutboundJson"])); err != nil {
			return "", fmt.Errorf("merge custom outbound %d: %w", profile.ID, err)
		}

		if profile.External && profile.CanMapping && !(needGlobal && profile.SkipMappingWhenLast) {
			mappingPort, err := reserveLocalPort()
			if err != nil {
				return "", err
			}
			mappingTag := fmt.Sprintf("%s-mapping-%d", chainTag, profile.ID)
			c.inbounds = append(c.inbounds, map[string]any{
				"type": "direct", "listen": configLocalhost, "listen_port": mappingPort,
				"tag": mappingTag, "override_address": anyString(bean["serverAddress"]),
				"override_port": anyInt(bean["serverPort"], 0),
			})
			pastInboundTag = mappingTag
			if external != nil {
				external.FinalAddress = configLocalhost
				external.FinalPort = mappingPort
			}
			if needGlobal {
				c.routeRules = append(c.routeRules, map[string]any{"inbound": []string{mappingTag}, "outbound": configTagDirect})
			}
		}
		c.outbounds = append(c.outbounds, outbound)
		pastOutbound = outbound
		pastProfile = profile
	}
	c.externalChains = append(c.externalChains, externalChain)
	return chainOutboundTag, nil
}

func (c *clientConfigCompiler) appendUserRules(ipv6Mode int) error {
	settings := c.request.Settings
	useFakeDNS := settings.EnableFakeDNS && !c.request.ForTest
	for _, rule := range c.request.Rules {
		routeRule := map[string]any{}
		if len(rule.UIDs) > 0 {
			routeRule["user_id"] = rule.UIDs
		}
		domains := splitList(rule.Domains)
		applyDomainRule(routeRule, domains)
		applyIPRule(routeRule, splitList(rule.IP))
		for _, tag := range anyStringSlice(routeRule["rule_set"]) {
			c.ruleSets = append(c.ruleSets, map[string]any{"type": "local", "tag": tag, "format": "binary", "path": tag})
		}
		ports, ranges := normalizeRulePortsValues(rule.Port)
		putSlice(routeRule, "port", ports)
		putSlice(routeRule, "port_range", ranges)
		sourcePorts, sourceRanges := normalizeRulePortsValues(rule.SourcePort)
		putSlice(routeRule, "source_port", sourcePorts)
		putSlice(routeRule, "source_port_range", sourceRanges)
		putSlice(routeRule, "network", splitList(rule.Network))
		putSlice(routeRule, "source_ip_cidr", splitList(rule.Source))
		putSlice(routeRule, "protocol", splitList(rule.Protocol))

		makeDNSRule := func() map[string]any {
			dnsRule := map[string]any{}
			if len(rule.UIDs) > 0 {
				dnsRule["user_id"] = rule.UIDs
			}
			applyDomainRule(dnsRule, domains)
			return dnsRule
		}
		if settings.EnableDNSRouting {
			switch rule.Outbound {
			case -1:
				dnsRule := makeDNSRule()
				dnsRule["server"] = "dns-direct"
				if !ruleMapEmpty(dnsRule, true) {
					c.dnsRules = append(c.dnsRules, dnsRule)
				}
			case 0:
				if useFakeDNS {
					dnsRule := makeDNSRule()
					dnsRule["server"] = "dns-fake"
					dnsRule["inbound"] = []string{"tun-in"}
					if !ruleMapEmpty(dnsRule, true) {
						c.dnsRules = append(c.dnsRules, dnsRule)
					}
				}
				dnsRule := makeDNSRule()
				dnsRule["server"] = "dns-remote"
				if !ruleMapEmpty(dnsRule, true) {
					c.dnsRules = append(c.dnsRules, dnsRule)
				}
			case -2:
				dnsRule := makeDNSRule()
				dnsRule["server"] = "dns-block"
				dnsRule["disable_cache"] = true
				if !ruleMapEmpty(dnsRule, true) {
					c.dnsRules = append(c.dnsRules, dnsRule)
				}
			}
		}

		outbound := ""
		switch rule.Outbound {
		case 0:
			outbound = configTagProxy
		case -1:
			outbound = configTagBypass
		case -2:
			outbound = configTagBlock
		default:
			if rule.Outbound == c.request.SelectedID {
				outbound = configTagProxy
			} else {
				outbound = c.tagMap[rule.Outbound]
			}
		}
		if outbound != "" {
			routeRule["outbound"] = outbound
		}
		if err := mergeJSONMap(routeRule, rule.Config); err != nil {
			return fmt.Errorf("merge route rule %d: %w", rule.ID, err)
		}
		if ruleMapEmpty(routeRule, false) {
			continue
		}
		if anyString(routeRule["outbound"]) == "" {
			c.warnings = append(c.warnings, fmt.Sprintf("%s: a non-existent outbound was specified", rule.Name))
			continue
		}
		if routeRule["outbound"] == configTagBlock {
			delete(routeRule, "outbound")
			routeRule["action"] = "reject"
		}
		c.routeRules = append(c.routeRules, routeRule)
	}
	_ = ipv6Mode
	return nil
}

func (c *clientConfigCompiler) appendDNS(ipv6Mode int) error {
	settings := c.request.Settings
	for host := range c.bypassDNSHosts {
		if net.ParseIP(host) == nil {
			c.directDNSDomains["full:"+host] = true
		}
	}
	remoteDNS := splitConfiguredDNS(settings.RemoteDNS)
	directDNS := splitConfiguredDNS(settings.DirectDNS)
	if len(directDNS) == 0 {
		return fmt.Errorf("no direct DNS, check your settings")
	}
	if !c.request.ForTest && len(remoteDNS) == 0 {
		return fmt.Errorf("no remote DNS, check your settings")
	}
	for _, address := range remoteDNS {
		candidate := address
		if !strings.Contains(candidate, "://") {
			candidate = "https://" + candidate
		}
		if parsed, err := url.Parse(candidate); err == nil && parsed.Hostname() != "" && net.ParseIP(parsed.Hostname()) == nil {
			c.directDNSDomains["full:"+parsed.Hostname()] = true
		}
	}
	c.dnsServers = append(c.dnsServers,
		map[string]any{"address": "rcode://success", "tag": "dns-block"},
		map[string]any{"address": "local", "tag": "dns-local", "detour": configTagDirect},
	)
	if err := c.appendDNSGroup(
		"dns-direct", directDNS, configTagDirect, "dns-local",
		settings.DirectDNSStrategy, ipv6Mode,
	); err != nil {
		return err
	}
	if !c.request.ForTest {
		if err := c.appendDNSGroup(
			"dns-remote", remoteDNS, "", "dns-direct",
			settings.RemoteDNSStrategy, ipv6Mode,
		); err != nil {
			return err
		}
	}
	if c.request.ForTest {
		c.dnsRules = []any{}
		return nil
	}
	c.routeRules = append([]any{
		map[string]any{"port": []int{53}, "action": "hijack-dns"},
		map[string]any{"protocol": []string{"dns"}, "action": "hijack-dns"},
	}, c.routeRules...)
	c.routeRules = append(c.routeRules, map[string]any{"outbound": configTagBypass, "ip_is_private": true})
	c.routeRules = append(c.routeRules, map[string]any{
		"ip_cidr":        []string{"224.0.0.0/3", "ff00::/8"},
		"source_ip_cidr": []string{"224.0.0.0/3", "ff00::/8"}, "action": "reject",
	})
	if settings.EnableFakeDNS {
		c.dnsServers = append(c.dnsServers, map[string]any{"address": "fakeip", "tag": "dns-fake", "strategy": "ipv4_only"})
		c.dnsRules = append(c.dnsRules, map[string]any{
			"inbound": []string{"tun-in"}, "server": "dns-fake", "disable_cache": true,
		})
	}
	c.dnsRules = append([]any{map[string]any{"outbound": []string{"any"}, "server": "dns-direct"}}, c.dnsRules...)
	if len(c.directDNSDomains) > 0 {
		domains := make([]string, 0, len(c.directDNSDomains))
		for domain := range c.directDNSDomains {
			domains = append(domains, domain)
		}
		sort.Strings(domains)
		rule := map[string]any{"server": "dns-direct"}
		applyDomainRule(rule, domains)
		c.dnsRules = append([]any{rule}, c.dnsRules...)
	}
	return nil
}

// appendDNSGroup keeps the public tags (dns-direct/dns-remote) stable while
// allowing each group to use a staggered race across multiple child servers.
// A single configured server stays on the native sing-box path.
func (c *clientConfigCompiler) appendDNSGroup(
	tag string,
	addresses []string,
	detour string,
	addressResolver string,
	configuredStrategy string,
	ipv6Mode int,
) error {
	addresses = uniqueNonEmptyStrings(addresses)
	if len(addresses) == 0 {
		return fmt.Errorf("no DNS servers configured for %s", tag)
	}
	strategy := automaticDNSStrategy(configuredStrategy, ipv6Mode)
	if len(addresses) == 1 {
		server := map[string]any{
			"address": addresses[0], "tag": tag, "strategy": strategy,
		}
		if detour != "" {
			server["detour"] = detour
		}
		if addressResolver != "" {
			server["address_resolver"] = addressResolver
		}
		c.dnsServers = append(c.dnsServers, server)
		return nil
	}

	children := make([]string, 0, len(addresses))
	for index, address := range addresses {
		childTag := fmt.Sprintf("%s-%d", tag, index)
		children = append(children, childTag)
		server := map[string]any{
			"address": address, "tag": childTag, "strategy": strategy,
		}
		if detour != "" {
			server["detour"] = detour
		}
		if addressResolver != "" {
			server["address_resolver"] = addressResolver
		}
		c.dnsServers = append(c.dnsServers, server)
	}
	c.dnsServers = append(c.dnsServers, map[string]any{
		"type":    dnsRaceType,
		"tag":     tag,
		"servers": children,
		"delay":   120,
	})
	return nil
}

func uniqueNonEmptyStrings(values []string) []string {
	result := make([]string, 0, len(values))
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if _, exists := seen[value]; exists {
			continue
		}
		seen[value] = struct{}{}
		result = append(result, value)
	}
	return result
}

func buildProfileOutboundMap(kind string, profile map[string]any, globalAllowInsecure bool) (map[string]any, error) {
	encodedProfile, err := json.Marshal(profile)
	if err != nil {
		return nil, err
	}
	encoded, err := BuildProfileOutbound(kind, string(encodedProfile), globalAllowInsecure)
	if err != nil {
		return nil, err
	}
	var result map[string]any
	if err := decodeMap(encoded, &result); err != nil {
		return nil, err
	}
	return result, nil
}

func decodeMap(input string, result *map[string]any) error {
	decoder := json.NewDecoder(strings.NewReader(input))
	decoder.UseNumber()
	return decoder.Decode(result)
}

func mergeJSONMap(destination map[string]any, input string) error {
	if strings.TrimSpace(input) == "" {
		return nil
	}
	var source map[string]any
	if err := decodeMap(input, &source); err != nil {
		return err
	}
	mergeConfigMap(destination, source)
	return nil
}

func mergeConfigMap(destination, source map[string]any) {
	for key, value := range source {
		if sourceMap, ok := value.(map[string]any); ok {
			if destinationMap, ok := destination[key].(map[string]any); ok {
				mergeConfigMap(destinationMap, sourceMap)
				destination[key] = destinationMap
				continue
			}
		}
		if sourceList, ok := value.([]any); ok {
			if strings.HasPrefix(key, "+") {
				realKey := strings.TrimPrefix(key, "+")
				destination[realKey] = append(sourceList, anySlice(destination[realKey])...)
				continue
			}
			if strings.HasSuffix(key, "+") {
				realKey := strings.TrimSuffix(key, "+")
				destination[realKey] = append(anySlice(destination[realKey]), sourceList...)
				continue
			}
		}
		destination[key] = value
	}
}

func applyDomainRule(rule map[string]any, values []string) {
	for _, value := range values {
		value = strings.TrimSpace(value)
		switch {
		case strings.HasPrefix(value, "geosite:"):
			appendString(rule, "rule_set", value)
		case strings.HasPrefix(value, "full:"):
			appendString(rule, "domain", strings.ToLower(strings.TrimPrefix(value, "full:")))
		case strings.HasPrefix(value, "domain:"):
			appendString(rule, "domain_suffix", strings.ToLower(strings.TrimPrefix(value, "domain:")))
		case strings.HasPrefix(value, "regexp:"):
			appendString(rule, "domain_regex", strings.ToLower(strings.TrimPrefix(value, "regexp:")))
		case strings.HasPrefix(value, "keyword:"):
			appendString(rule, "domain_keyword", strings.ToLower(strings.TrimPrefix(value, "keyword:")))
		case value != "":
			appendString(rule, "domain_suffix", strings.ToLower(value))
		}
	}
}

func applyIPRule(rule map[string]any, values []string) {
	for _, value := range values {
		value = strings.TrimSpace(value)
		switch {
		case value == "geoip:private":
			rule["ip_is_private"] = true
		case strings.HasPrefix(value, "geoip:"):
			appendString(rule, "rule_set", value)
		case value != "":
			appendString(rule, "ip_cidr", value)
		}
	}
}

func appendString(target map[string]any, key, value string) {
	values := anyStringSlice(target[key])
	target[key] = append(values, value)
}

func normalizeRulePortsValues(input string) ([]int, []string) {
	ports := []int{}
	ranges := []string{}
	seenPorts := map[int]bool{}
	seenRanges := map[string]bool{}
	for _, token := range splitList(input) {
		if strings.Contains(token, ":") {
			bounds := strings.SplitN(token, ":", 2)
			start, startErr := strconv.Atoi(strings.TrimSpace(bounds[0]))
			end, endErr := strconv.Atoi(strings.TrimSpace(bounds[1]))
			if startErr == nil && endErr == nil && start >= 1 && end >= start && end <= 65535 {
				normalized := fmt.Sprintf("%d:%d", start, end)
				if !seenRanges[normalized] {
					seenRanges[normalized] = true
					ranges = append(ranges, normalized)
				}
			}
			continue
		}
		port, err := strconv.Atoi(strings.TrimSpace(token))
		if err == nil && port >= 1 && port <= 65535 && !seenPorts[port] {
			seenPorts[port] = true
			ports = append(ports, port)
		}
	}
	return ports, ranges
}

func reserveLocalPort() (int, error) {
	listener, err := net.Listen("tcp4", configLocalhost+":0")
	if err != nil {
		return 0, fmt.Errorf("reserve local port: %w", err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()
	return port, nil
}

func profileServer(bean map[string]any) string {
	if anyInt(bean["type"], -1) == 1 && anyString(bean["config"]) != "" {
		var custom map[string]any
		if decodeMap(anyString(bean["config"]), &custom) == nil {
			if server := anyString(custom["server"]); server != "" {
				return server
			}
		}
	}
	return anyString(bean["serverAddress"])
}

func reverseProfiles(values []*clientConfigProfile) {
	for left, right := 0, len(values)-1; left < right; left, right = left+1, right-1 {
		values[left], values[right] = values[right], values[left]
	}
}

func automaticDNSStrategy(configured string, ipv6Mode int) string {
	if configured != "" {
		return configured
	}
	switch ipv6Mode {
	case 0:
		return "ipv4_only"
	case 1:
		return "prefer_ipv4"
	case 2:
		return "prefer_ipv6"
	case 3:
		return "ipv6_only"
	default:
		return ""
	}
}

func logLevelName(level int) string {
	if value := map[int]string{0: "panic", 1: "warn", 2: "info", 3: "debug", 4: "trace"}[level]; value != "" {
		return value
	}
	return "info"
}

func splitConfiguredDNS(input string) []string {
	result := []string{}
	for _, value := range strings.Split(input, "\n") {
		if value = strings.TrimSpace(value); value != "" && !strings.HasPrefix(value, "#") {
			result = append(result, value)
		}
	}
	return result
}

func deduplicateRuleSets(values []any) []any {
	result := []any{}
	seen := map[string]bool{}
	for _, value := range values {
		ruleSet := value.(map[string]any)
		tag := anyString(ruleSet["tag"])
		if tag == "" || seen[tag] {
			continue
		}
		seen[tag] = true
		result = append(result, ruleSet)
	}
	return result
}

func ruleMapEmpty(rule map[string]any, dns bool) bool {
	for key, value := range rule {
		if dns && (key == "server" || key == "disable_cache") {
			continue
		}
		if !dns && (key == "outbound" || key == "action") {
			continue
		}
		switch typed := value.(type) {
		case []string:
			if len(typed) > 0 {
				return false
			}
		case []int:
			if len(typed) > 0 {
				return false
			}
		case []any:
			if len(typed) > 0 {
				return false
			}
		case string:
			if typed != "" {
				return false
			}
		case bool:
			if typed {
				return false
			}
		default:
			if typed != nil {
				return false
			}
		}
	}
	return true
}

func putSlice[T any](target map[string]any, key string, values []T) {
	if len(values) > 0 {
		target[key] = values
	}
}

func cloneMap(source map[string]any) map[string]any {
	result := make(map[string]any, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

func anySlice(value any) []any {
	result, _ := value.([]any)
	return result
}

func anyStringSlice(value any) []string {
	switch typed := value.(type) {
	case []string:
		return typed
	case []any:
		result := make([]string, 0, len(typed))
		for _, item := range typed {
			if text := anyString(item); text != "" {
				result = append(result, text)
			}
		}
		return result
	default:
		return nil
	}
}

// normalizeWireGuardReserved accepts the UI's decimal triplet while retaining
// already encoded values for compatibility.
func normalizeWireGuardReserved(value string) string {
	parts := splitList(value)
	if len(parts) != 3 {
		return value
	}
	decoded := make([]byte, 3)
	for index, part := range parts {
		parsed, err := strconv.Atoi(strings.Trim(strings.TrimSpace(part), "[]"))
		if err != nil || parsed < 0 || parsed > 255 {
			return value
		}
		decoded[index] = byte(parsed)
	}
	return base64.StdEncoding.EncodeToString(decoded)
}
