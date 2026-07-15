# NekoPilot project ledger

## Release candidate — 2026-07-15

- Product: `NekoPilot 1.5.0` (`com.nekopilot.android`)
- Android baseline: `MatsuriDayo/NekoBoxForAndroid` commit `5768494d8ae3c74a057bb6d46c0f8dc071b0d821`
- Core: official stable sing-box `1.13.14`, plus the audited Neko Android integration patch in
  `buildScript/lib/core/patches/sing-box-1.13.14-neko.patch`
- Core source archive SHA-256: `d18294eb00128743b1dbf1d5f4f01902bdfd59a2d2858cda809abe5351a9cd40`

### Completed hardening and fixes

- Unsafe YAML/INI parsing, unbounded imports/decompression/logging, path traversal, unsafe WebView
  navigation, cleartext networking, backup leakage, crash-report secret leakage, and plugin path/signature
  validation were hardened and covered by tests.
- Mixed proxy and Clash API use per-install credentials. The unauthenticated Android HTTP-proxy
  compatibility path was removed; LAN binding remains authenticated.
- Shortcut control activities are unexported. Boot broadcasts validate their action.
- Embedded release signing material was removed. Release builds require an external key.
- Room no longer permits main-thread queries. Public preferences use a thread-safe memory cache with
  cross-process invalidation, and profile database migrations explicitly cover schema versions 1 through 6.
- Traffic collection has an owned coroutine lifecycle, atomic state updates, joined shutdown, and final
  persistence before the native instance closes.
- Hysteria 1 and Hysteria 2 standalone JSON/YAML imports are supported, including IPv6 and multi-port servers.
- The client uses an independent package and no longer checks the upstream NekoBox release channel for
  incompatible application updates.
- Go is pinned to 1.26.5. Native dependencies were refreshed by the sing-box 1.13.14 module graph.

### Verification boundary

Host-side Go tests, tagged native-core tests, JVM unit tests, Android Lint, migration-test APK compilation,
and split release builds are required before packaging. Device-only VPN authorization, live tunnelling,
launcher behavior, and OEM-specific background behavior still require a physical Android device or emulator.
