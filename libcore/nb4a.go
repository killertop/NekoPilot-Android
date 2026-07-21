package libcore

import (
	"fmt"
	"libcore/device"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	_ "unsafe"

	"log"

	"github.com/matsuridayo/libneko/neko_common"
	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/option"
	"golang.org/x/sys/unix"
)

//go:linkname resourcePaths github.com/sagernet/sing-box/constant.resourcePaths
var resourcePaths []string

const (
	minLogSizeKB = 50
	maxLogSizeKB = 10 * 1024
)

func NekoLogPrintln(s string) {
	log.Println(s)
}

func NekoLogClear() {
	neko_log.LogWriter.Truncate()
}

func ForceGc() {
	// Android already dispatches this call away from the main thread and gates concurrent
	// requests. Keep the work synchronous so that gate covers the actual GC instead of only the
	// goroutine creation.
	debug.FreeOSMemory()
}

func InitCore(process, cachePath, internalAssets, externalAssets string,
	maxLogSizeKb int32, logEnable bool,
	if1 NB4AInterface, if2 BoxPlatformInterface, if3 LocalDNSTransport,
) (failure string) {
	defer device.DeferPanicToError("InitCore", func(err error) {
		log.Println(err)
		failure = err.Error()
	})
	isBgProcess = strings.HasSuffix(process, ":bg")

	neko_common.RunMode = neko_common.RunMode_NekoBoxForAndroid
	intfNB4A = if1
	intfBox = if2
	useProcfs = intfBox.UseProcFS()
	gLocalDNSTransport = newPlatformTransport(if3, "", option.LocalDNSServerOptions{})

	// Working dir
	tmp := filepath.Join(cachePath, "../no_backup")
	if err := os.MkdirAll(tmp, 0755); err != nil {
		return fmt.Sprintf("create working directory: %v", err)
	}
	if err := os.Chdir(tmp); err != nil {
		return fmt.Sprintf("change working directory: %v", err)
	}

	// sing-box fs
	resourcePaths = append(resourcePaths, externalAssets)
	externalAssetsPath = externalAssets
	internalAssetsPath = internalAssets

	// Set up log
	maxLogSizeKb = normalizeLogSizeKB(maxLogSizeKb)
	neko_log.LogWriterDisable = !logEnable
	neko_log.TruncateOnStart = isBgProcess
	neko_log.SetupLog(int(maxLogSizeKb)*1024, filepath.Join(cachePath, "neko.log"))
	installSanitizingLogWriter()

	// Set up some component
	go func() {
		defer device.DeferPanicToError("InitCore-go", func(err error) { log.Println(err) })
		device.GoDebug(process)

		// certs
		pem, err := os.ReadFile(externalAssetsPath + "ca.pem")
		if err == nil {
			updateRootCACerts(pem)
		}

	}()

	return ""
}

func normalizeLogSizeKB(size int32) int32 {
	if size < minLogSizeKB {
		return minLogSizeKB
	}
	if size > maxLogSizeKB {
		return maxLogSizeKB
	}
	return size
}

func sendFdToProtect(fd int, path string) error {
	socketFd, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		return fmt.Errorf("failed to create unix socket: %w", err)
	}
	defer unix.Close(socketFd)

	var timeout unix.Timeval
	timeout.Usec = 100 * 1000

	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &timeout)
	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_SNDTIMEO, &timeout)

	err = unix.Connect(socketFd, &unix.SockaddrUnix{Name: path})
	if err != nil {
		return fmt.Errorf("failed to connect: %w", err)
	}

	err = unix.Sendmsg(socketFd, nil, unix.UnixRights(fd), nil, 0)
	if err != nil {
		return fmt.Errorf("failed to send: %w", err)
	}

	dummy := []byte{1}
	n, err := unix.Read(socketFd, dummy)
	if err != nil {
		return fmt.Errorf("failed to receive: %w", err)
	}
	if n != 1 {
		return fmt.Errorf("socket closed unexpectedly")
	}
	return nil
}
