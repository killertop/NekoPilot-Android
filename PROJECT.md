# NekoPilot project ledger

## Release candidate — 2026-07-20

- Product: `NekoPilot 1.5.2` (`com.nekopilot.android`)
- Android baseline: `MatsuriDayo/NekoBoxForAndroid` commit `5768494d8ae3c74a057bb6d46c0f8dc071b0d821`
- Core: official stable sing-box `1.13.14`, plus the audited Neko Android integration patch in
  `buildScript/lib/core/patches/sing-box-1.13.14-neko.patch`
- Core source archive SHA-256: `d18294eb00128743b1dbf1d5f4f01902bdfd59a2d2858cda809abe5351a9cd40`

### Completed hardening and fixes

- Unsafe YAML/INI parsing, unbounded imports/decompression, path traversal, unsafe WebView
  navigation, cleartext networking, backup leakage, and plugin path/signature
  validation were hardened and covered by tests.
- The mixed proxy uses per-install credentials. The unauthenticated Android HTTP-proxy compatibility
  path was removed; LAN binding remains authenticated.
- Shortcut control activities are unexported. Boot broadcasts validate their action.
- Embedded release signing material was removed. Release builds require an external key.
- Room no longer permits main-thread queries. Public preferences use a thread-safe memory cache with
  cross-process invalidation, and profile database migrations explicitly cover schema versions 1 through 9.
- Runtime no longer collects, persists, broadcasts, or displays per-profile traffic counters or connection
  speed. The sing-box integration therefore does not install a stats tracker or expose a stats polling API.
- Hysteria 1 and Hysteria 2 standalone JSON/YAML imports are supported, including IPv6 and multi-port servers.
- Portable parsing and codec work was moved into the Go core: route-port normalization, bounded YAML conversion,
  WireGuard INI parsing, Hysteria/Hy2 share-link parsing, and bounded zlib encoding/decoding. The removed Kotlin
  implementations are covered by Go golden, malformed-input, IPv6, truncation, and decompression-limit tests.
- Portable profile handling now crosses Android/Go through one stable JSON DTO: Go performs bounded batch parsing
  and link export for VMess, VLESS, Trojan, Shadowsocks, SOCKS, HTTP, Naive, Hysteria, TUIC, AnyTLS and Trojan-Go;
  it also normalizes Clash YAML, sing-box JSON and Base64 subscription documents without changing the Room/Kryo ABI.
- Supported built-in profile beans are converted to sing-box outbounds in Go, where the pinned sing-box option model
  validates the result. The legacy WireGuard compatibility path remains until it can become a sing-box 1.13 endpoint;
  Kotlin retains Android lifecycle, database, plugin and UI responsibilities.
- The distributable client excludes the Clash API/YACD dashboard and packages only English and Simplified Chinese
  (`zh-rCN`) resources to reduce the APK without removing proxy protocols.
- Exported sing-box configurations are decoded by the pinned Go `option.Options` model. Runtime configurations
  are decoded once while creating the native service, avoiding a duplicate full parse during connection startup.
- Subscription reconciliation now uses indexed O(n) duplicate detection and a single Room transaction for
  additions, updates, and deletions. Large duplicate sets are covered by a 5,000-entry regression test.
- Preference writes use an ordered asynchronous database writer instead of blocking every caller; a flush barrier
  preserves cross-process consistency before service startup.
- Package install/remove events update the package cache incrementally. Node lists use stable IDs and `DiffUtil`,
  remain ordered by measured latency, and no longer retain the removed home-search implementation.
- Configuration building caches groups, entities, and resolved chains, bulk-loads missing profiles, and serializes
  custom configuration layers without an intermediate JSON map round-trip.
- Latency results, deletions, and drag-order changes use batched Room writes. WAL journaling and a
  `(groupId, userOrder)` index reduce reader/writer contention and accelerate ordered group queries.
- Application selectors reuse the incremental package cache and dispatch list differences instead of rescanning all
  installed packages and rebinding every visible row whenever the screen opens or its search changes.
- The client uses an independent package and no longer checks the upstream NekoBox release channel for
  incompatible application updates.
- Go is pinned to 1.26.5. Native dependencies were refreshed by the sing-box 1.13.14 module graph.

### Verification boundary

Host-side Go tests, tagged native-core tests, JVM unit tests, Android Lint, migration-test APK compilation,
and split release builds are required before packaging. Device-only VPN authorization, live tunnelling,
launcher behavior, and OEM-specific background behavior still require a physical Android device or emulator.
