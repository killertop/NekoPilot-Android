# Official libbox 1.14.0-beta.1 provenance

## Pin

- Previous core: `v1.14.0-alpha.48` / `fa36eb769a200e9558c414a36eb16da9a2446ea9`
- Current core: `v1.14.0-beta.1` / `8bc6787c7ff785e5f6343241affdadd5ca239bd7`
- Official source: `https://github.com/SagerNet/sing-box`, resolved from the immutable tag above.
- Verified fallback source archive: `https://codeload.github.com/SagerNet/sing-box/tar.gz/refs/tags/v1.14.0-beta.1`
  (`SHA-256 9bf9beb33e0363ced2bc2dc1c080251dfadaa25273e294f5592a7b5154378d94`).

The build script verifies the exact Git commit when it populates its default cache. A supplied
source directory is for an already verified official checkout or archive only.

## Verified build (2026-07-24)

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
| NDK | `25.2.9519653` (`25.0.8775105` SDK directory) |
| gomobile | `github.com/sagernet/gomobile v0.1.12` |
| Android API / ABI | `23` / `arm64-v8a` |
| Build tags | `with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0,with_low_memory` |
| Linker defaults | `runtime.godebugDefault=multipathtcp=0,tlssha1=1,tlsunsafeekm=1` |

Resulting `app/libs/libbox.aar`:

- SHA-256: `9e3faaf3d03563ae883941d7a39561cebd35e82399e2fc6dff615c9b361f9031`
- Contains only `jni/arm64-v8a/libbox.so`; the native ELF is stripped AArch64.
- The native binary embeds `1.14.0-beta.1` and `go1.26.5`.
- The AAR manifest declares `minSdkVersion=23`; ProGuard retains only the Go and
  `io.nekohasekai` bridge classes required by gomobile.

## Product choices

- Keep the existing low-memory, QUIC, WireGuard and uTLS build surface. `with_low_memory` is
  consumed by the Go dependency graph and remains intentional.
- Do not add OpenVPN, OpenConnect, OIDC, Fortinet host checks, enterprise UI, Tailscale UI, or
  additional product-specific native runtimes.
- Do not enable DNS `race`, `speculative`, tagged response evaluation, local/DHCP preference, or
  search-domain routing: the app currently uses a direct bootstrap resolver plus direct/proxied
  HTTPS resolvers, so those features would add queries or blur the intended DNS boundary without
  a demonstrated user benefit.
