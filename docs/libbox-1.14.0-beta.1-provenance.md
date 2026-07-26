# Official libbox 1.14.0-beta.1 provenance

## Pin

- Previous core: `v1.14.0-alpha.48` / `fa36eb769a200e9558c414a36eb16da9a2446ea9`
- Current core: `v1.14.0-beta.1` / `8bc6787c7ff785e5f6343241affdadd5ca239bd7`
- Official source: `https://github.com/SagerNet/sing-box`, resolved from the immutable tag above.
- Verified fallback source archive: `https://codeload.github.com/SagerNet/sing-box/tar.gz/refs/tags/v1.14.0-beta.1`
  (`SHA-256 9bf9beb33e0363ced2bc2dc1c080251dfadaa25273e294f5592a7b5154378d94`).

The build script verifies the exact Git commit for both its default cache and a caller-supplied
`SING_BOX_SOURCE`. A supplied directory must therefore be a Git checkout at the pinned commit;
an extracted archive is useful for independent digest verification but is not accepted as a build
input without an auditable Git revision.

## Verified build (2026-07-25)

```bash
SING_BOX_SOURCE=/path/to/sing-box-1.14.0-beta.1 \
NEKOPILOT_BUILD_CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/nekopilot" \
NEKOPILOT_LIBBOX_ABIS=arm64-v8a \
./scripts/build-official-libbox.sh
```

| Input | Value |
| --- | --- |
| Go | `go1.26.5 darwin/arm64` |
| Java | OpenJDK `17.0.18` |
| Android SDK / Build Tools | `35` / `35.0.1` |
| NDK | Repository pin in [`.android-ndk-version`](../.android-ndk-version) (r28b) |
| gomobile | `github.com/sagernet/gomobile v0.1.12` |
| Android API / ABI | `23` / `arm64-v8a` |
| Build tags | `with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,badlinkname,tfogo_checklinkname0,with_low_memory` |
| Linker defaults | `runtime.godebugDefault=multipathtcp=0,tlssha1=1,tlsunsafeekm=1` |

Resulting `app/libs/libbox.aar`:

- SHA-256: `e9ce4d56ada112d71e84b2d5d05bffda1e49d4c1de316c58004ae60ea95aec20`
- Contains only `jni/arm64-v8a/libbox.so`; the native ELF is stripped AArch64.
- The native binary embeds `1.14.0-beta.1` and `go1.26.5`.
- The AAR manifest declares `minSdkVersion=23`; ProGuard retains only the Go and
  `io.nekohasekai` bridge classes required by gomobile.

## Product choices

- Keep the existing low-memory, QUIC, WireGuard and uTLS build surface, and enable
  `with_naive_outbound` because Naive is importable and configurable in the product. The added
  Cronet dependency requires Android NDK r28 to link reproducibly. `with_low_memory` is consumed
  by the Go dependency graph and remains intentional.
- Do not add OpenVPN, OpenConnect, OIDC, Fortinet host checks, enterprise UI, Tailscale UI, or
  additional product-specific native runtimes.
- Do not enable DNS `race`, `speculative`, tagged response evaluation, or search-domain routing:
  the app currently uses a direct bootstrap resolver plus direct/proxied HTTPS resolvers, so those
  features would add queries or blur the intended DNS boundary without a demonstrated user
  benefit. The local-DNS preference rule is intentionally enabled for LAN discovery; its
  `preferred_by` value must be the local transport tag (`dns-system`), not the transport type
  (`local`), because the pinned libbox runtime resolves that field by tag.
