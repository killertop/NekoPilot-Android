# Building NekoPilot locally

## Requirements

- JDK 17
- Android SDK 35, Build Tools 35.0.1, and NDK 25.0.8775105
- Go 1.26.5 (pinned by `libcore/go.mod` for current standard-library security fixes)

Create an ignored `local.properties` containing `sdk.dir=/absolute/path/to/Android/sdk`.

## Build

```bash
./run lib core
./gradlew --no-daemon --max-workers=1 --no-parallel app:assembleOssDebug
```

The native bootstrap downloads the official sing-box 1.13.14 source archive, verifies its SHA-256,
applies the committed Neko Android integration patch, and checks out immutable libneko and gomobile
revisions before creating the ignored `app/libs/libcore.aar`. It rebuilds the pinned gomobile tools
when the active Go toolchain changes.

If `proxy.golang.org` is unreachable on the current network, select an accessible module proxy for
that invocation, for example `GOPROXY=https://goproxy.cn,direct ./run lib core`. Do not commit a
machine- or region-specific proxy setting.

## Release signing

Never store a signing key in this repository. Supply all four values through environment variables
or ignored `local.properties`:

```properties
KEYSTORE_FILE=/absolute/path/to/nekopilot-release.jks
KEYSTORE_PASS=...
ALIAS_NAME=...
ALIAS_PASS=...
```

Debug builds use the standard Android debug identity. Creating the long-term production identity
requires explicit authorization and a secure offline backup.
