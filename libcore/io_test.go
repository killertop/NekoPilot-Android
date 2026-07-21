package libcore

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"
)

func TestSecureArchivePath(t *testing.T) {
	base := t.TempDir()
	want := filepath.Join(base, "rules", "geoip-cn.srs")
	got, err := secureArchivePath(base, "rules/geoip-cn.srs")
	if err != nil || got != want {
		t.Fatalf("unexpected safe path: %q, %v", got, err)
	}
	for _, path := range []string{"../escape", "rules/../../escape", "..\\escape", "/absolute"} {
		if _, err := secureArchivePath(base, path); err == nil {
			t.Fatalf("expected unsafe path %q to fail", path)
		}
	}
}

func TestUnzipRejectsTraversalBeforeWriting(t *testing.T) {
	root := t.TempDir()
	archivePath := filepath.Join(root, "malicious.zip")
	archive, err := os.Create(archivePath)
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(archive)
	entry, err := writer.Create("../escape")
	if err == nil {
		_, err = entry.Write([]byte("bad"))
	}
	if closeErr := writer.Close(); err == nil {
		err = closeErr
	}
	if closeErr := archive.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		t.Fatal(err)
	}

	destination := filepath.Join(root, "output")
	if err := Unzip(archivePath, destination); err == nil {
		t.Fatal("expected traversal archive to fail")
	}
	if _, err := os.Stat(filepath.Join(root, "escape")); !os.IsNotExist(err) {
		t.Fatal("archive escaped destination")
	}
}
