package libcore

import (
	"archive/zip"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/ulikunitz/xz"
)

const (
	maxArchiveEntries      = 4096
	maxArchiveExtractBytes = 512 << 20
)

func Unxz(archive string, path string) error {
	i, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer i.Close()
	r, err := xz.NewReader(i)
	if err != nil {
		return err
	}
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	_, copyErr := copyLimited(o, r, maxArchiveExtractBytes)
	closeErr := o.Close()
	err = E.Errors(copyErr, closeErr)
	if err != nil {
		_ = os.Remove(path)
	}
	return err
}

func Unzip(archive string, path string) error {
	r, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer r.Close()

	basePath, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	if len(r.File) > maxArchiveEntries {
		return fmt.Errorf("archive contains too many entries: %d", len(r.File))
	}
	var declaredSize uint64
	for _, file := range r.File {
		if _, err := secureArchivePath(basePath, file.Name); err != nil {
			return err
		}
		declaredSize += file.UncompressedSize64
		if declaredSize > maxArchiveExtractBytes {
			return fmt.Errorf("archive expands beyond %d bytes", maxArchiveExtractBytes)
		}
	}

	err = os.MkdirAll(basePath, 0o755)
	if err != nil {
		return err
	}

	var extractedBytes int64
	for _, file := range r.File {
		filePath, err := secureArchivePath(basePath, file.Name)
		if err != nil {
			return err
		}

		if file.FileInfo().IsDir() {
			err = os.MkdirAll(filePath, 0o755)
			if err != nil {
				return err
			}
			continue
		}
		if err = os.MkdirAll(filepath.Dir(filePath), 0o755); err != nil {
			return err
		}

		newFile, err := os.Create(filePath)
		if err != nil {
			return err
		}

		zipFile, err := file.Open()
		if err != nil {
			newFile.Close()
			return err
		}

		var errs error
		remaining := int64(maxArchiveExtractBytes) - extractedBytes
		var written int64
		written, err = copyLimited(newFile, zipFile, remaining)
		extractedBytes += written
		errs = E.Errors(errs, err)
		errs = E.Errors(errs, common.Close(zipFile, newFile))
		if errs != nil {
			_ = os.Remove(filePath)
			return errs
		}
	}

	return nil
}

func secureArchivePath(basePath string, entryName string) (string, error) {
	normalized := strings.ReplaceAll(entryName, "\\", "/")
	cleaned := filepath.Clean(normalized)
	if cleaned == "." || filepath.IsAbs(cleaned) || cleaned == ".." ||
		strings.HasPrefix(cleaned, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("unsafe archive path %q", entryName)
	}
	path := filepath.Join(basePath, cleaned)
	relative, err := filepath.Rel(basePath, path)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("unsafe archive path %q", entryName)
	}
	return path, nil
}
