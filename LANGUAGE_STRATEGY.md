# NekoPilot language strategy

## Boundary

- Android UI, lifecycle, permissions, Room, settings, imports, subscription updates, rule assets,
  configuration generation, and node-test orchestration are Kotlin.
- The proxy data plane is the pinned, unmodified official sing-box `experimental/libbox` AAR.
- There is no product Go source, custom JNI bridge, Java source, or Rust source in this repository.

Kotlin owns product policy and invokes libbox only from Android service-layer adapters. UI classes do
not call a CLI or native APIs directly. This preserves Android VPN permission, foreground-service,
TUN file-descriptor, per-app routing, and lifecycle handling in Kotlin while retaining the upstream
Go proxy core as its supported mobile interface.

`scripts/verify-language-boundaries.sh` rejects product Go, Java, Rust, unapproved native libraries,
and any APK runtime other than the pinned official `libbox.so`.

The official `CommandServer` requires the upstream Clash state collector to support its Android
platform log adapter. It is compiled into libbox but NekoPilot never emits a Clash API listener,
REST endpoint, or YACD asset.
