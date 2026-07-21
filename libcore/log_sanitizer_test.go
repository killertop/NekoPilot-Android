package libcore

import (
	"bytes"
	"strings"
	"testing"
)

func TestSanitizeLogMessageRedactsProxyLinksAndCredentials(t *testing.T) {
	message := "trojan://secret@example.com:443?password=hunter2 " +
		"https://user:pass@example.com/path?token=abc " +
		`{"private_key":"key material, with spaces","uuid":"id"} ` +
		"'token' = 'alpha beta, gamma' password=plain\n" +
		"password: hello world\n"
	sanitized := sanitizeLogMessage(message)
	for _, secret := range []string{
		"hunter2", "user:pass", "token=abc", "key material", "with spaces",
		`"uuid":"id"`, "alpha beta", "gamma", "plain", "hello", "world",
	} {
		if strings.Contains(sanitized, secret) {
			t.Fatalf("secret %q remains in %q", secret, sanitized)
		}
	}
	if !strings.Contains(sanitized, "[redacted") {
		t.Fatalf("redaction marker missing from %q", sanitized)
	}
}

func TestSanitizeLogMessageTruncatesAtUnicodeBoundary(t *testing.T) {
	message := strings.Repeat("a", maxLogMessageUTF16Units-1) + "猫🐈secret"
	sanitized := sanitizeLogMessage(message)
	if !strings.HasSuffix(sanitized, "猫\n[log entry truncated]\n") {
		t.Fatalf("unexpected unicode truncation suffix: %q", sanitized[len(sanitized)-64:])
	}
	if strings.Contains(sanitized, "secret") {
		t.Fatal("content after the limit was retained")
	}
}

func TestSanitizingLogWriterProtectsAllSinkCallers(t *testing.T) {
	var destination bytes.Buffer
	message := "request https://user:pass@example.com/path?token=secret\n"
	written, err := (sanitizingLogWriter{destination: &destination}).Write([]byte(message))
	if err != nil {
		t.Fatal(err)
	}
	if written != len(message) {
		t.Fatalf("writer consumed %d bytes, want %d", written, len(message))
	}
	result := destination.String()
	for _, secret := range []string{"user:pass", "token=secret"} {
		if strings.Contains(result, secret) {
			t.Fatalf("sink leaked %q in %q", secret, result)
		}
	}
}

func TestSanitizingLogWriterSeparatesTruncatedEntries(t *testing.T) {
	var destination bytes.Buffer
	writer := sanitizingLogWriter{destination: &destination}
	longMessage := strings.Repeat("a", maxLogMessageUTF16Units+1)
	if _, err := writer.Write([]byte(longMessage)); err != nil {
		t.Fatal(err)
	}
	if _, err := writer.Write([]byte("next entry\n")); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(destination.String(), "[log entry truncated]\nnext entry\n") {
		t.Fatalf("successive log entries were joined: %q", destination.String()[maxLogMessageUTF16Units-32:])
	}
}
