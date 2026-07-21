package libcore

import (
	"encoding/json"
	"fmt"
	"strings"
)

type normalizedProfileSet struct {
	Profiles   []map[string]any `json:"profiles"`
	Duplicates []string         `json:"duplicates"`
}

// NormalizeProfileSet owns subscription naming and address/type deduplication.
// It preserves every profile field so Android can deserialize directly into
// the stable Room/Kryo bean ABI.
func NormalizeProfileSet(input string, deduplicate bool) (string, error) {
	var profiles []map[string]any
	decoder := json.NewDecoder(strings.NewReader(input))
	decoder.UseNumber()
	if err := decoder.Decode(&profiles); err != nil {
		return "", fmt.Errorf("decode profiles: %w", err)
	}
	result, err := normalizeProfileMaps(profiles, deduplicate)
	if err != nil {
		return "", err
	}
	encoded, err := json.Marshal(result)
	return string(encoded), err
}

func normalizeProfileMaps(profiles []map[string]any, deduplicate bool) (normalizedProfileSet, error) {
	if len(profiles) > maxProfileLinkCount {
		return normalizedProfileSet{}, fmt.Errorf("too many profiles")
	}
	usedNames := make(map[string]bool, len(profiles))
	nextSuffix := make(map[string]int, len(profiles))
	for _, profile := range profiles {
		originalName := anyString(profile["name"])
		base := profileDisplayName(profile)
		name, suffix := base, nextSuffix[base]
		for usedNames[name] {
			suffix++
			name = fmt.Sprintf("%s (%d)", base, suffix)
		}
		usedNames[name] = true
		nextSuffix[base] = suffix
		if originalName != "" || name != base {
			profile["name"] = name
		}
	}
	result := normalizedProfileSet{Profiles: profiles, Duplicates: []string{}}
	if deduplicate {
		seen := make(map[string]int, len(profiles))
		firstNames := make(map[string]string, len(profiles))
		unique := make([]map[string]any, 0, len(profiles))
		for _, profile := range profiles {
			key := profileDedupKey(profile)
			index, exists := seen[key]
			if !exists {
				seen[key] = len(unique)
				firstNames[key] = profileDisplayName(profile)
				unique = append(unique, profile)
				continue
			}
			if firstName := firstNames[key]; firstName != "" {
				name := strings.TrimSuffix(firstName, fmt.Sprintf(" (%d)", index))
				if name != "" {
					result.Duplicates = append(result.Duplicates, fmt.Sprintf("%s (%d)", name, index))
				}
				firstNames[key] = ""
			}
			result.Duplicates = append(result.Duplicates, fmt.Sprintf("%s (%d)", profileDisplayName(profile), index))
		}
		result.Profiles = unique
	}
	return result, nil
}

func profileDisplayName(profile map[string]any) string {
	if name := anyString(profile["name"]); name != "" {
		return name
	}
	host := anyString(profile["serverAddress"])
	port := anyInt(profile["serverPort"], 1080)
	if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
		host = "[" + host + "]"
	}
	return fmt.Sprintf("%s:%d", host, port)
}

func profileDedupKey(profile map[string]any) string {
	if anyString(profile["kind"]) == "config" {
		return "config\x00" + anyString(profile["config"])
	}
	kind := anyString(profile["kind"])
	if kind == "vless" {
		kind = "vmess"
	}
	if kind == "hysteria2" {
		kind = "hysteria"
	}
	return kind + "\x00" + anyString(profile["serverAddress"]) + "\x00" + fmt.Sprint(anyInt(profile["serverPort"], 1080))
}
