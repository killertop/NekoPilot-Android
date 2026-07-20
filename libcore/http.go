package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/ech"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

const (
	maxHTTPContentBytes  = 32 << 20
	maxHTTPDownloadBytes = 256 << 20
)

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	SetTimeout(timeoutMillis int64)
	TrySocks5(port int32, username string, password string)
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
	WriteToProgress(path string, listener HTTPProgress) error
}

type HTTPProgress interface {
	OnProgress(downloaded int64, total int64)
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls            tls.Config
	h1h2Transport  http.Transport
	h1h2Client     http.Client
	requestTimeout time.Duration
	trySocks5      bool
	tryH3Direct    bool
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	client.requestTimeout = 10 * time.Minute
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Client.Timeout = client.requestTimeout
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	client.h1h2Transport.DialContext = (&net.Dialer{
		Timeout:   15 * time.Second,
		KeepAlive: 30 * time.Second,
	}).DialContext
	client.h1h2Transport.TLSHandshakeTimeout = 15 * time.Second
	client.h1h2Transport.ResponseHeaderTimeout = 30 * time.Second
	client.h1h2Transport.ExpectContinueTimeout = 5 * time.Second
	client.h1h2Transport.IdleConnTimeout = 90 * time.Second
	return client
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) SetTimeout(timeoutMillis int64) {
	if timeoutMillis <= 0 {
		return
	}
	c.requestTimeout = time.Duration(timeoutMillis) * time.Millisecond
	c.h1h2Client.Timeout = c.requestTimeout
}

func (c *httpClient) TrySocks5(port int32, username string, password string) {
	dialer := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			_, err = socks.ClientHandshake5(
				socksConn,
				socks5.CommandConnect,
				metadata.ParseSocksaddr(addr),
				username,
				password,
			)
			if err != nil {
				_ = socksConn.Close()
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			return socksConn, err
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request http.Request
}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	contentCopy := bytes.Clone(content)
	r.request.GetBody = func() (io.ReadCloser, error) {
		return io.NopCloser(bytes.NewReader(contentCopy)), nil
	}
	r.request.Body, _ = r.request.GetBody()
	r.request.ContentLength = int64(len(contentCopy))
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func (r *httpRequest) Execute() (HTTPResponse, error) {
	defer device.DeferPanicToError("http execute", func(err error) { log.Println(err) })
	// full direct
	if r.tryH3Direct && !r.trySocks5 {
		return r.doH3Direct()
	}
	response, err := r.h1h2Client.Do(&r.request)
	if err != nil {
		// trySocks5 && tryH3Direct
		if r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := &httpResponse{Response: response}
	if response.StatusCode != http.StatusOK {
		return nil, errors.New(httpResp.errorString())
	}
	return httpResp, nil
}

type requestFunc func() (response *http.Response, err error)

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	timeout := r.requestTimeout
	if timeout <= 0 || timeout > 10*time.Second {
		timeout = 10 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	successCh := make(chan *http.Response, 1)
	var finalErr error
	var failedCount atomic.Uint32
	var successCount atomic.Uint32
	var mu sync.Mutex

	funcs := []requestFunc{
		// Http(s) With Ech
		func() (response *http.Response, err error) {
			request, err := r.cloneRequest(ctx)
			if err != nil {
				return nil, err
			}
			transport := &http.Transport{
				DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
					var d net.Dialer
					c, err := d.DialContext(ctx, network, addr)
					if err != nil {
						return c, err
					}
					domain := addr
					if host, _, _ := net.SplitHostPort(addr); host != "" {
						domain = host
					}
					echTls := ech.NewECHClientConfig(domain, &r.tls, gLocalDNSTransport)
					return echTls.Client(ctx, c)
				},
				DisableKeepAlives: true,
			}
			defer transport.CloseIdleConnections()
			echClient := &http.Client{
				Transport: transport,
			}
			response, err = echClient.Do(request)
			return bufferHTTPResponse(response, err)
		},
		// H3 HTTPS
		func() (response *http.Response, err error) {
			request, err := r.cloneRequest(ctx)
			if err != nil {
				return nil, err
			}
			transport := &http3.Transport{
				TLSClientConfig: r.tls.Clone(),
				QUICConfig: &quic.Config{
					MaxIdleTimeout: time.Second,
				},
			}
			defer transport.Close()
			h3Client := &http.Client{
				Transport: transport,
			}
			response, err = h3Client.Do(request)
			return bufferHTTPResponse(response, err)
		},
	}

	if r.request.URL.Scheme == "http" {
		funcs = funcs[:1]
	}

	for i, f := range funcs {
		go func(f requestFunc) {
			defer device.DeferPanicToError("http", func(err error) { log.Println(err) })
			defer func() {
				if successCount.Load() == 0 {
					if failedCount.Add(1) >= uint32(len(funcs)) {
						// 全部失败了
						cancel()
					}
				}
			}()

			var t string
			switch i {
			case 0:
				t = "http(s)"
			case 1:
				t = "h3"
			}

			// 执行HTTP请求
			rsp, err := f()
			if rsp == nil || err != nil {
				mu.Lock()
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %w", t, err))
				mu.Unlock()
				if rsp != nil && rsp.Body != nil {
					rsp.Body.Close()
				}
				return
			}

			// 处理 HTTP 状态码
			if rsp.StatusCode != http.StatusOK {
				hr := &httpResponse{Response: rsp}
				err = fmt.Errorf("%s: %s", t, hr.errorString())
				mu.Lock()
				finalErr = errors.Join(finalErr, err)
				mu.Unlock()
				return
			}

			select {
			case successCh <- rsp:
				// 第一个成功的请求，不要关闭 body
				successCount.Add(1)
			default:
				rsp.Body.Close()
			}
		}(f)
	}

	select {
	case result := <-successCh:
		cancel()
		return &httpResponse{Response: result}, nil
	case <-ctx.Done():
		mu.Lock()
		err := finalErr
		mu.Unlock()
		if err == nil {
			err = ctx.Err()
		}
		return nil, err
	}
}

func (r *httpRequest) cloneRequest(ctx context.Context) (*http.Request, error) {
	request := r.request.Clone(ctx)
	if r.request.Body != nil {
		if r.request.GetBody == nil {
			return nil, errors.New("HTTP request body cannot be replayed")
		}
		body, err := r.request.GetBody()
		if err != nil {
			return nil, err
		}
		request.Body = body
	}
	return request, nil
}

func bufferHTTPResponse(response *http.Response, err error) (*http.Response, error) {
	if err != nil || response == nil {
		return response, err
	}
	content, readErr := readAllLimited(response.Body, maxHTTPContentBytes)
	closeErr := response.Body.Close()
	if readErr != nil {
		return nil, readErr
	}
	if closeErr != nil {
		return nil, closeErr
	}
	response.Body = io.NopCloser(bytes.NewReader(content))
	response.ContentLength = int64(len(content))
	return response, nil
}

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

func (h *httpResponse) errorString() string {
	_, _ = h.GetContent()
	return fmt.Sprint("HTTP ", h.Status)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.Body.Close()
		h.content, h.contentError = readAllLimited(h.Body, maxHTTPContentBytes)
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	return h.WriteToProgress(path, nil)
}

func (h *httpResponse) WriteToProgress(path string, listener HTTPProgress) error {
	defer h.Body.Close()
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	total := h.ContentLength
	if total < 0 {
		total = 0
	}
	if listener != nil {
		listener.OnProgress(0, total)
	}
	_, err = copyLimitedProgress(file, h.Body, maxHTTPDownloadBytes, total, listener)
	if err != nil {
		_ = os.Remove(path)
	}
	return err
}

func readAllLimited(reader io.Reader, maxBytes int64) ([]byte, error) {
	content, err := io.ReadAll(io.LimitReader(reader, maxBytes+1))
	if err != nil {
		return nil, err
	}
	if int64(len(content)) > maxBytes {
		return nil, fmt.Errorf("HTTP response exceeds %d bytes", maxBytes)
	}
	return content, nil
}

func copyLimited(destination io.Writer, source io.Reader, maxBytes int64) (int64, error) {
	return copyLimitedProgress(destination, source, maxBytes, 0, nil)
}

func copyLimitedProgress(destination io.Writer, source io.Reader, maxBytes int64, total int64, listener HTTPProgress) (int64, error) {
	writer := destination
	var progress *httpProgressWriter
	if listener != nil {
		progress = &httpProgressWriter{destination: destination, listener: listener, total: total}
		writer = progress
	}
	written, err := io.Copy(writer, io.LimitReader(source, maxBytes+1))
	if listener != nil && progress.reported != written {
		listener.OnProgress(written, total)
	}
	if err != nil {
		return written, err
	}
	if written > maxBytes {
		return written, fmt.Errorf("HTTP download exceeds %d bytes", maxBytes)
	}
	return written, nil
}

type httpProgressWriter struct {
	destination io.Writer
	listener    HTTPProgress
	total       int64
	written     int64
	reported    int64
}

func (w *httpProgressWriter) Write(content []byte) (int, error) {
	written, err := w.destination.Write(content)
	w.written += int64(written)
	if w.written-w.reported >= 64*1024 || err != nil {
		w.reported = w.written
		w.listener.OnProgress(w.written, w.total)
	}
	return written, err
}
