package libcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"libcore/procfs"
	"log"
	"net/netip"
	"strings"
	"syscall"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
)

var boxPlatformInterfaceInstance adapter.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct{}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState() adapter.WIFIState {
	state := intfBox.WIFIState()
	separator := strings.LastIndexByte(state, ',')
	if separator < 0 {
		return adapter.WIFIState{}
	}
	return adapter.WIFIState{
		SSID:  state[:separator],
		BSSID: state[separator+1:],
	}
}

func (w *boxPlatformInterfaceWrapper) Initialize(n adapter.NetworkManager) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int) error {
	// call protect_path
	if !isBgProcess {
		err := sendFdToProtect(fd, "protect_path")
		// Standalone node tests run in the UI process even when no VPN service exists. In that
		// state there is intentionally no protect socket, and normal system routing is already
		// correct. Other failures remain fatal so an active VPN cannot silently absorb tests.
		if protectServerUnavailable(err) {
			return nil
		}
		return err
	}
	// bg process call VPNService
	return intfBox.AutoDetectInterfaceControl(int32(fd))
}

func protectServerUnavailable(err error) bool {
	return errors.Is(err, syscall.ENOENT) || errors.Is(err, syscall.ECONNREFUSED)
}

func (w *boxPlatformInterfaceWrapper) UsePlatformInterface() bool { return true }

func (w *boxPlatformInterfaceWrapper) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, E.New("android: unsupported uid options")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return nil, E.New("android: unsupported android_user option")
	}
	a, err := json.Marshal(options)
	if err != nil {
		return nil, fmt.Errorf("encode tun options: %w", err)
	}
	b, err := json.Marshal(platformOptions)
	if err != nil {
		return nil, fmt.Errorf("encode tun platform options: %w", err)
	}
	tunFd, err := intfBox.OpenTun(string(a), string(b))
	if err != nil {
		return nil, fmt.Errorf("intfBox.OpenTun: %v", err)
	}
	// Do you want to close it?
	tunFd, err = syscall.Dup(tunFd)
	if err != nil {
		return nil, fmt.Errorf("syscall.Dup: %v", err)
	}
	//
	options.FileDescriptor = int(tunFd)
	return tun.New(*options)
}

func (w *boxPlatformInterfaceWrapper) ProcessPlatformOptions(option.TunPlatformOptions) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) CloseTun() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	return &interfaceMonitorStub{}
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, errors.New("platform network interfaces are disabled")
}

func (w *boxPlatformInterfaceWrapper) NetworkExtensionIncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNotification() bool { return false }

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *adapter.Notification) error {
	return nil
}

func (s *boxPlatformInterfaceWrapper) SystemCertificates() []string {
	return nil
}

// Android not using

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

func (w *boxPlatformInterfaceWrapper) RequestPermissionForWIFIState() error { return nil }

func (w *boxPlatformInterfaceWrapper) UsePlatformWIFIMonitor() bool { return true }

func (w *boxPlatformInterfaceWrapper) UsePlatformConnectionOwnerFinder() bool { return true }

func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	var uid int32
	if useProcfs {
		var network string
		switch request.IpProtocol {
		case syscall.IPPROTO_TCP:
			network = N.NetworkTCP
		case syscall.IPPROTO_UDP:
			network = N.NetworkUDP
		default:
			return nil, E.New("unknown IP protocol: ", request.IpProtocol)
		}
		sourceAddress, err := netip.ParseAddr(request.SourceAddress)
		if err != nil {
			return nil, fmt.Errorf("invalid source address: %w", err)
		}
		destinationAddress, err := netip.ParseAddr(request.DestinationAddress)
		if err != nil {
			return nil, fmt.Errorf("invalid destination address: %w", err)
		}
		if request.SourcePort < 0 || request.SourcePort > 65535 ||
			request.DestinationPort < 0 || request.DestinationPort > 65535 {
			return nil, errors.New("connection owner request contains an invalid port")
		}
		source := netip.AddrPortFrom(sourceAddress, uint16(request.SourcePort))
		destination := netip.AddrPortFrom(destinationAddress, uint16(request.DestinationPort))
		uid = procfs.ResolveSocketByProcSearch(network, source, destination)
		if uid == -1 {
			return nil, E.New("procfs: not found")
		}
	} else {
		var err error
		uid, err = intfBox.FindConnectionOwner(request.IpProtocol, request.SourceAddress, request.SourcePort, request.DestinationAddress, request.DestinationPort)
		if err != nil {
			return nil, err
		}
	}
	packageName, err := intfBox.PackageNameByUid(uid)
	if err != nil {
		return nil, fmt.Errorf("resolve package for uid %d: %w", uid, err)
	}
	return &adapter.ConnectionOwner{UserId: uid, AndroidPackageNames: []string{packageName}}, nil
}

func (w *boxPlatformInterfaceWrapper) MyInterfaceAddress() []netip.Addr { return nil }

func (w *boxPlatformInterfaceWrapper) UsePlatformNeighborResolver() bool { return false }

func (w *boxPlatformInterfaceWrapper) StartNeighborMonitor(adapter.NeighborUpdateListener) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) CloseNeighborMonitor(adapter.NeighborUpdateListener) error {
	return nil
}

// NekoPilot is an Android proxy client, not a privileged host-management or
// L3 bridge app. Keep these optional 1.14 platform capabilities unavailable.
func (w *boxPlatformInterfaceWrapper) UsePlatformShell() bool { return false }

func (w *boxPlatformInterfaceWrapper) CheckPlatformShell() error {
	return errors.New("platform shell is unavailable on Android")
}

func (w *boxPlatformInterfaceWrapper) OpenShellSession(*adapter.PlatformUser, string, []string, string, int32, int32) (adapter.ShellSession, error) {
	return nil, errors.New("platform shell is unavailable on Android")
}

func (w *boxPlatformInterfaceWrapper) LookupUser(string) (*adapter.PlatformUser, error) {
	return nil, errors.New("platform users are unavailable on Android")
}

func (w *boxPlatformInterfaceWrapper) LookupSFTPServer() (string, error) {
	return "", errors.New("SFTP server is unavailable on Android")
}

func (w *boxPlatformInterfaceWrapper) ReadSystemSSHHostKey() ([]byte, error) {
	return nil, errors.New("system SSH host key is unavailable on Android")
}

func (w *boxPlatformInterfaceWrapper) TailscaleHostname() string { return "" }

func (w *boxPlatformInterfaceWrapper) UsePlatformBridge() bool { return false }

func (w *boxPlatformInterfaceWrapper) CreateBridge(adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, errors.New("L3 bridge is unavailable on Android")
}

// io.Writer

var disableSingBoxLog = false

func (w *boxPlatformInterfaceWrapper) Write(p []byte) (n int, err error) {
	if !disableSingBoxLog {
		log.Print(string(p))
	}
	return len(p), nil
}
