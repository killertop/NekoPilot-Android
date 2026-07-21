package libcore

import (
	"io"
	"log"
	"os"
	"regexp"

	"github.com/matsuridayo/libneko/neko_log"
)

func init() {
	// Protect the small pre-InitCore window as well; InitCore later redirects this same wrapper to
	// Neko's rotating writer.
	log.SetOutput(sanitizingLogWriter{destination: os.Stderr})
}

const maxLogMessageUTF16Units = 64 * 1024

var (
	proxyLinkPattern = regexp.MustCompile(
		`(?i)\b(?:sn|ss|ssr|socks4a?|socks5|vmess|vless|trojan(?:-go)?|naive\+(?:https|quic)|hysteria2?|hy2|tuic|anytls)://[^\s]+`,
	)
	uriUserInfoPattern = regexp.MustCompile(`([A-Za-z][A-Za-z0-9+.-]*://)[^/@\s]+@`)
	querySecretPattern = regexp.MustCompile(
		`(?i)([?&](?:password|passwd|token|secret|auth|key|uuid|psk)=)[^&\s]+`,
	)
	doubleQuotedSecretPattern = regexp.MustCompile(
		`(?i)(["']?(?:password|passwd|token|secret|private[_-]?key|auth|uuid|psk)["']?\s*[:=]\s*)"(?:[^"\\]|\\.)*"`,
	)
	singleQuotedSecretPattern = regexp.MustCompile(
		`(?i)(["']?(?:password|passwd|token|secret|private[_-]?key|auth|uuid|psk)["']?\s*[:=]\s*)'(?:[^'\\]|\\.)*'`,
	)
	unquotedSecretPattern = regexp.MustCompile(
		`(?i)(["']?(?:password|passwd|token|secret|private[_-]?key|auth|uuid|psk)["']?\s*[:=]\s*)[^"',\r\n}\]]+`,
	)
)

// sanitizingLogWriter is installed under Go's process-wide standard logger after the Neko log
// file is opened. This keeps Go, sing-box adapter, and Kotlin-originated messages behind the same
// redaction and size boundary instead of relying on every call site to remember it.
type sanitizingLogWriter struct {
	destination io.Writer
}

func (w sanitizingLogWriter) Write(content []byte) (int, error) {
	sanitized := sanitizeLogMessage(string(content))
	written, err := io.WriteString(w.destination, sanitized)
	if err != nil {
		return 0, err
	}
	if written != len(sanitized) {
		return 0, io.ErrShortWrite
	}
	// io.Writer reports consumption of the caller's input, not the transformed output.
	return len(content), nil
}

func installSanitizingLogWriter() {
	if neko_log.LogWriter != nil {
		log.SetOutput(sanitizingLogWriter{destination: neko_log.LogWriter})
	}
}

func writeSanitizedPlatformLog(message string) {
	if neko_log.LogWriter == nil {
		log.Print(sanitizeLogMessage(message))
		return
	}
	_, _ = sanitizingLogWriter{destination: neko_log.LogWriter}.Write([]byte(message))
}

func sanitizeLogMessage(message string) string {
	sanitized := proxyLinkPattern.ReplaceAllString(message, "[redacted proxy link]")
	sanitized = uriUserInfoPattern.ReplaceAllString(sanitized, "${1}[redacted]@")
	sanitized = querySecretPattern.ReplaceAllString(sanitized, "${1}[redacted]")
	sanitized = doubleQuotedSecretPattern.ReplaceAllString(sanitized, `${1}"[redacted]"`)
	sanitized = singleQuotedSecretPattern.ReplaceAllString(sanitized, `${1}'[redacted]'`)
	sanitized = unquotedSecretPattern.ReplaceAllString(sanitized, "${1}[redacted]")
	truncated, changed := truncateUTF16(sanitized, maxLogMessageUTF16Units)
	if changed {
		return truncated + "\n[log entry truncated]\n"
	}
	return truncated
}

func truncateUTF16(value string, maxUnits int) (string, bool) {
	units := 0
	for index, character := range value {
		width := 1
		if character > 0xffff {
			width = 2
		}
		if units+width > maxUnits {
			return value[:index], true
		}
		units += width
	}
	return value, false
}
