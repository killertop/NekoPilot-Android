package libcore

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

func TestReadAllLimitedRejectsOversizedResponse(t *testing.T) {
	if _, err := readAllLimited(bytes.NewBufferString("123456789"), 8); err == nil {
		t.Fatal("expected oversized response to fail")
	}
	content, err := readAllLimited(bytes.NewBufferString("12345678"), 8)
	if err != nil || string(content) != "12345678" {
		t.Fatalf("unexpected bounded read result: %q, %v", content, err)
	}
}

func TestCopyLimitedRejectsOversizedDownload(t *testing.T) {
	var destination bytes.Buffer
	if _, err := copyLimited(&destination, bytes.NewBufferString("123456789"), 8); err == nil {
		t.Fatal("expected oversized download to fail")
	}
}

func TestRequestBodyCanBeReplayed(t *testing.T) {
	request := NewHttpClient().NewRequest().(*httpRequest)
	request.SetContentString("payload")
	first, err := request.cloneRequest(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	second, err := request.cloneRequest(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	firstBody, _ := io.ReadAll(first.Body)
	secondBody, _ := io.ReadAll(second.Body)
	if string(firstBody) != "payload" || string(secondBody) != "payload" {
		t.Fatalf("request body was not replayed: %q, %q", firstBody, secondBody)
	}
}

func TestTrySocks5UsesUsernameAndPassword(t *testing.T) {
	port, serverErr := startSocks5AuthServer(t, "nekopilot", "secret", true)
	client := NewHttpClient().(*httpClient)
	client.TrySocks5(port, "nekopilot", "secret")

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	conn, err := client.h1h2Transport.DialContext(ctx, "tcp", "example.com:80")
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
	if err := <-serverErr; err != nil {
		t.Fatal(err)
	}
}

func TestTrySocks5RejectsBadCredentialsWithoutDirectFallback(t *testing.T) {
	port, serverErr := startSocks5AuthServer(t, "nekopilot", "secret", false)
	client := NewHttpClient().(*httpClient)
	client.TrySocks5(port, "nekopilot", "wrong")
	client.TryH3Direct()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, err := client.h1h2Transport.DialContext(ctx, "tcp", "example.com:80")
	if !errors.Is(err, errFailConnectSocks5) {
		t.Fatalf("expected authenticated SOCKS failure, got %v", err)
	}
	if err := <-serverErr; err != nil {
		t.Fatal(err)
	}
}

func startSocks5AuthServer(t *testing.T, wantUser, wantPassword string, accept bool) (int32, <-chan error) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	result := make(chan error, 1)
	go func() {
		defer listener.Close()
		conn, err := listener.Accept()
		if err == nil {
			defer conn.Close()
			err = serveSocks5Auth(conn, wantUser, wantPassword, accept)
		}
		result <- err
	}()
	return int32(listener.Addr().(*net.TCPAddr).Port), result
}

func serveSocks5Auth(conn net.Conn, wantUser, wantPassword string, accept bool) error {
	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil {
		return err
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}
	if header[0] != 5 || !containsByte(methods, 2) {
		return errors.New("client did not offer SOCKS5 username/password authentication")
	}
	if _, err := conn.Write([]byte{5, 2}); err != nil {
		return err
	}

	if _, err := io.ReadFull(conn, header); err != nil {
		return err
	}
	user := make([]byte, int(header[1]))
	if _, err := io.ReadFull(conn, user); err != nil {
		return err
	}
	if _, err := io.ReadFull(conn, header[:1]); err != nil {
		return err
	}
	password := make([]byte, int(header[0]))
	if _, err := io.ReadFull(conn, password); err != nil {
		return err
	}
	valid := string(user) == wantUser && string(password) == wantPassword && accept
	status := byte(1)
	if valid {
		status = 0
	}
	if _, err := conn.Write([]byte{1, status}); err != nil || !valid {
		return err
	}

	request := make([]byte, 4)
	if _, err := io.ReadFull(conn, request); err != nil {
		return err
	}
	if request[0] != 5 || request[1] != 1 {
		return errors.New("unexpected SOCKS5 command")
	}
	var addressLength int
	switch request[3] {
	case 1:
		addressLength = net.IPv4len
	case 4:
		addressLength = net.IPv6len
	case 3:
		length := []byte{0}
		if _, err := io.ReadFull(conn, length); err != nil {
			return err
		}
		addressLength = int(length[0])
	default:
		return errors.New("unexpected SOCKS5 address type")
	}
	addressAndPort := make([]byte, addressLength+2)
	if _, err := io.ReadFull(conn, addressAndPort); err != nil {
		return err
	}
	_ = binary.BigEndian.Uint16(addressAndPort[addressLength:])
	_, err := conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0})
	return err
}

func containsByte(values []byte, want byte) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}
