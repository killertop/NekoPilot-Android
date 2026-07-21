package libcore

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
)

const (
	profileBatchVersion       = 1
	profileBatchTypeProfiles  = 1
	profileBatchTypeSubscribe = 2
	profileBatchTypeNormalize = 3
	maxProfileBatchBytes      = 32 * 1024 * 1024
)

var profileBatchMagic = [4]byte{'N', 'P', 'B', '1'}

type profileBatch struct {
	kind              byte
	profiles          []map[string]any
	metadata          []string
	hasUnnamedSkipped bool
}

func writeBatchString(buffer *bytes.Buffer, value string) error {
	data := []byte(value)
	if len(data) > maxPortableConfigBytes {
		return fmt.Errorf("profile batch value is too large")
	}
	if err := binary.Write(buffer, binary.BigEndian, uint32(len(data))); err != nil {
		return err
	}
	_, err := buffer.Write(data)
	return err
}

func readBatchString(reader *bytes.Reader) (string, error) {
	var size uint32
	if err := binary.Read(reader, binary.BigEndian, &size); err != nil {
		return "", err
	}
	if size > maxPortableConfigBytes || uint64(size) > uint64(reader.Len()) {
		return "", fmt.Errorf("invalid profile batch value size")
	}
	data := make([]byte, int(size))
	if _, err := reader.Read(data); err != nil {
		return "", err
	}
	return string(data), nil
}

func encodeProfileBatch(batch profileBatch) ([]byte, error) {
	if len(batch.profiles) > maxProfileLinkCount || len(batch.metadata) > maxProfileLinkCount {
		return nil, fmt.Errorf("profile batch contains too many entries")
	}
	buffer := bytes.NewBuffer(make([]byte, 0, 4096))
	buffer.Write(profileBatchMagic[:])
	buffer.WriteByte(profileBatchVersion)
	buffer.WriteByte(batch.kind)
	if err := binary.Write(buffer, binary.BigEndian, uint32(len(batch.profiles))); err != nil {
		return nil, err
	}
	for _, profile := range batch.profiles {
		kind := anyString(profile["kind"])
		if kind == "" {
			return nil, fmt.Errorf("profile batch entry is missing kind")
		}
		encoded, err := json.Marshal(profile)
		if err != nil {
			return nil, fmt.Errorf("encode profile batch entry: %w", err)
		}
		if err = writeBatchString(buffer, kind); err != nil {
			return nil, err
		}
		if err = writeBatchString(buffer, string(encoded)); err != nil {
			return nil, err
		}
		if buffer.Len() > maxProfileBatchBytes {
			return nil, fmt.Errorf("profile batch is too large")
		}
	}
	if err := binary.Write(buffer, binary.BigEndian, uint32(len(batch.metadata))); err != nil {
		return nil, err
	}
	for _, value := range batch.metadata {
		if err := writeBatchString(buffer, value); err != nil {
			return nil, err
		}
	}
	if batch.hasUnnamedSkipped {
		buffer.WriteByte(1)
	} else {
		buffer.WriteByte(0)
	}
	if buffer.Len() > maxProfileBatchBytes {
		return nil, fmt.Errorf("profile batch is too large")
	}
	return buffer.Bytes(), nil
}

func decodeProfileBatch(input []byte, expectedKind byte) (profileBatch, error) {
	if len(input) < 11 || len(input) > maxProfileBatchBytes {
		return profileBatch{}, fmt.Errorf("invalid profile batch size")
	}
	reader := bytes.NewReader(input)
	var magic [4]byte
	if _, err := reader.Read(magic[:]); err != nil || magic != profileBatchMagic {
		return profileBatch{}, fmt.Errorf("invalid profile batch magic")
	}
	version, _ := reader.ReadByte()
	kind, _ := reader.ReadByte()
	if version != profileBatchVersion || kind != expectedKind {
		return profileBatch{}, fmt.Errorf("unsupported profile batch")
	}
	var profileCount uint32
	if err := binary.Read(reader, binary.BigEndian, &profileCount); err != nil || profileCount > uint32(maxProfileLinkCount) {
		return profileBatch{}, fmt.Errorf("invalid profile batch count")
	}
	batch := profileBatch{kind: kind, profiles: make([]map[string]any, 0, profileCount)}
	for index := uint32(0); index < profileCount; index++ {
		kindValue, err := readBatchString(reader)
		if err != nil || kindValue == "" {
			return profileBatch{}, fmt.Errorf("decode profile batch kind")
		}
		profileJSON, err := readBatchString(reader)
		if err != nil {
			return profileBatch{}, err
		}
		var profile map[string]any
		decoder := json.NewDecoder(bytes.NewBufferString(profileJSON))
		decoder.UseNumber()
		if err = decoder.Decode(&profile); err != nil || profile == nil || !decoderAtEOF(decoder) {
			return profileBatch{}, fmt.Errorf("decode profile batch entry")
		}
		profile["kind"] = kindValue
		batch.profiles = append(batch.profiles, profile)
	}
	var metadataCount uint32
	if err := binary.Read(reader, binary.BigEndian, &metadataCount); err != nil || metadataCount > uint32(maxProfileLinkCount) {
		return profileBatch{}, fmt.Errorf("invalid profile batch metadata")
	}
	batch.metadata = make([]string, 0, metadataCount)
	for index := uint32(0); index < metadataCount; index++ {
		value, err := readBatchString(reader)
		if err != nil {
			return profileBatch{}, err
		}
		batch.metadata = append(batch.metadata, value)
	}
	flag, err := reader.ReadByte()
	if err != nil || flag > 1 || reader.Len() != 0 {
		return profileBatch{}, fmt.Errorf("invalid profile batch trailer")
	}
	batch.hasUnnamedSkipped = flag == 1
	return batch, nil
}

func profilesFromJSON(encoded string) ([]map[string]any, error) {
	var profiles []map[string]any
	decoder := json.NewDecoder(bytes.NewBufferString(encoded))
	decoder.UseNumber()
	if err := decoder.Decode(&profiles); err != nil {
		return nil, err
	}
	return profiles, nil
}

// ParseProfileLinksBinary avoids a large JSON array crossing JNI for airport imports.
func ParseProfileLinksBinary(input string) ([]byte, error) {
	profiles, _, err := parseProfileLinksDetailed(input)
	if err != nil {
		return nil, err
	}
	return encodeProfileBatch(profileBatch{kind: profileBatchTypeProfiles, profiles: profiles})
}

// ParseProfileDocumentBinary returns the same profiles through the bounded binary transport.
func ParseProfileDocumentBinary(input string) ([]byte, error) {
	encoded, err := ParseProfileDocument(input)
	if err != nil {
		return nil, err
	}
	profiles, err := profilesFromJSON(encoded)
	if err != nil {
		return nil, err
	}
	return encodeProfileBatch(profileBatch{kind: profileBatchTypeProfiles, profiles: profiles})
}

// ParseSubscriptionDocumentBinary includes skipped-provider metadata after the profile records.
func ParseSubscriptionDocumentBinary(input string) ([]byte, error) {
	encoded, err := ParseSubscriptionDocument(input)
	if err != nil {
		return nil, err
	}
	var document struct {
		Profiles          []map[string]any `json:"profiles"`
		SkippedNames      []string         `json:"skippedNames"`
		HasUnnamedSkipped bool             `json:"hasUnnamedSkipped"`
	}
	if err = json.Unmarshal([]byte(encoded), &document); err != nil {
		return nil, err
	}
	return encodeProfileBatch(profileBatch{
		kind:              profileBatchTypeSubscribe,
		profiles:          document.Profiles,
		metadata:          document.SkippedNames,
		hasUnnamedSkipped: document.HasUnnamedSkipped,
	})
}

// NormalizeProfileSetBinary accepts and returns bounded binary profile records.
func NormalizeProfileSetBinary(input []byte, deduplicate bool) ([]byte, error) {
	batch, err := decodeProfileBatch(input, profileBatchTypeProfiles)
	if err != nil {
		return nil, err
	}
	result, err := normalizeProfileMaps(batch.profiles, deduplicate)
	if err != nil {
		return nil, err
	}
	return encodeProfileBatch(profileBatch{
		kind:     profileBatchTypeNormalize,
		profiles: result.Profiles,
		metadata: result.Duplicates,
	})
}
