package libcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"strconv"
	"strings"
)

const (
	maxReleaseMetadataBytes = 4 * 1024 * 1024
	maxReleaseNotesRunes    = 6_000
	maxReleaseAssets        = 4_096
)

var (
	repositoryPattern = regexp.MustCompile(`^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`)
	versionPattern    = regexp.MustCompile(`(?i)^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?((?:[-+]).+)?$`)
)

type githubReleaseMetadata struct {
	TagName string `json:"tag_name"`
	Body    string `json:"body"`
	HTMLURL string `json:"html_url"`
	Assets  []struct {
		Name               string `json:"name"`
		BrowserDownloadURL string `json:"browser_download_url"`
		Size               int64  `json:"size"`
	} `json:"assets"`
}

type appReleaseMetadata struct {
	Version         string `json:"version"`
	Notes           string `json:"notes"`
	DownloadPageURL string `json:"download_page_url"`
}

type ruleAssetReleaseMetadata struct {
	Tag         string `json:"tag"`
	DownloadURL string `json:"download_url"`
	Size        int64  `json:"size"`
	ChecksumURL string `json:"checksum_url"`
}

func parseGitHubRelease(input string) (githubReleaseMetadata, error) {
	if len(input) == 0 || len(input) > maxReleaseMetadataBytes {
		return githubReleaseMetadata{}, errors.New("release metadata is empty or too large")
	}
	var release githubReleaseMetadata
	if err := json.Unmarshal([]byte(input), &release); err != nil {
		return githubReleaseMetadata{}, fmt.Errorf("decode release metadata: %w", err)
	}
	release.TagName = strings.TrimSpace(release.TagName)
	if release.TagName == "" || len(release.TagName) > 128 {
		return githubReleaseMetadata{}, errors.New("invalid release version")
	}
	if len(release.Assets) > maxReleaseAssets {
		return githubReleaseMetadata{}, errors.New("release contains too many assets")
	}
	return release, nil
}

func validateRepository(repository string) error {
	if !repositoryPattern.MatchString(repository) {
		return errors.New("invalid GitHub repository")
	}
	return nil
}

func validGitHubReleaseURL(raw, repository, section string) bool {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme != "https" || parsed.Hostname() != "github.com" || parsed.User != nil {
		return false
	}
	prefix := "/" + repository + "/releases/" + section + "/"
	return strings.HasPrefix(parsed.EscapedPath(), prefix)
}

// ParseAppRelease validates and normalizes GitHub metadata used by Android's update prompt.
func ParseAppRelease(input, repository string) (string, error) {
	if err := validateRepository(repository); err != nil {
		return "", err
	}
	release, err := parseGitHubRelease(input)
	if err != nil {
		return "", err
	}
	downloadPage := strings.TrimSpace(release.HTMLURL)
	if !validGitHubReleaseURL(downloadPage, repository, "tag") {
		return "", errors.New("invalid release download page")
	}
	notes := strings.TrimSpace(strings.ReplaceAll(release.Body, "\r\n", "\n"))
	noteRunes := []rune(notes)
	if len(noteRunes) > maxReleaseNotesRunes {
		notes = string(noteRunes[:maxReleaseNotesRunes])
	}
	version := strings.TrimPrefix(strings.TrimPrefix(release.TagName, "v"), "V")
	encoded, err := json.Marshal(appReleaseMetadata{
		Version:         version,
		Notes:           notes,
		DownloadPageURL: downloadPage,
	})
	if err != nil {
		return "", fmt.Errorf("encode app release metadata: %w", err)
	}
	return string(encoded), nil
}

type numericVersion struct {
	parts      [3]int64
	preRelease bool
}

func parseNumericVersion(value string) (numericVersion, bool) {
	match := versionPattern.FindStringSubmatch(strings.TrimSpace(value))
	if match == nil {
		return numericVersion{}, false
	}
	var version numericVersion
	for index := range version.parts {
		component := match[index+1]
		if component == "" {
			continue
		}
		parsed, err := strconv.ParseInt(component, 10, 32)
		if err != nil {
			return numericVersion{}, false
		}
		version.parts[index] = parsed
	}
	version.preRelease = strings.HasPrefix(match[4], "-")
	return version, true
}

// IsRemoteVersionNewer compares the three numeric release components and stable/prerelease state.
func IsRemoteVersionNewer(remote, current string) bool {
	remoteVersion, remoteValid := parseNumericVersion(remote)
	currentVersion, currentValid := parseNumericVersion(current)
	if !remoteValid || !currentValid {
		return false
	}
	for index := range remoteVersion.parts {
		if remoteVersion.parts[index] != currentVersion.parts[index] {
			return remoteVersion.parts[index] > currentVersion.parts[index]
		}
	}
	return !remoteVersion.preRelease && currentVersion.preRelease
}

// ParseRuleAssetRelease selects and validates a rule database plus its checksum sidecar from a
// GitHub release response. Download and atomic file installation remain in Android/Kotlin.
func ParseRuleAssetRelease(input, repository, fileName string, maxAssetBytes int64) (string, error) {
	if err := validateRepository(repository); err != nil {
		return "", err
	}
	if fileName != geoipDat && fileName != geositeDat {
		return "", errors.New("unsupported rule asset")
	}
	if maxAssetBytes <= 0 {
		return "", errors.New("invalid maximum rule asset size")
	}
	release, err := parseGitHubRelease(input)
	if err != nil {
		return "", err
	}
	var result ruleAssetReleaseMetadata
	result.Tag = release.TagName
	for _, asset := range release.Assets {
		rawURL := strings.TrimSpace(asset.BrowserDownloadURL)
		if !validGitHubReleaseURL(rawURL, repository, "download") {
			continue
		}
		switch asset.Name {
		case fileName:
			result.DownloadURL = rawURL
			result.Size = asset.Size
		case fileName + ".sha256sum":
			result.ChecksumURL = rawURL
		}
	}
	if result.Size <= 0 || result.Size > maxAssetBytes {
		return "", fmt.Errorf("%s has an invalid release size", fileName)
	}
	if result.DownloadURL == "" {
		return "", fmt.Errorf("%s is missing from release %s", fileName, release.TagName)
	}
	if result.ChecksumURL == "" {
		return "", fmt.Errorf("%s checksum is missing from release %s", fileName, release.TagName)
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		return "", fmt.Errorf("encode rule asset release metadata: %w", err)
	}
	return string(encoded), nil
}
