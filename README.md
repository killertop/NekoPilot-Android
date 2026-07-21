# NekoPilot for Android

NekoPilot for Android is an Android-first proxy client built with Kotlin, Go, and sing-box. It
focuses on a clear everyday workflow: import a subscription or a standalone node
link, select a node, connect through the chosen mode, and inspect the connection result.

> This repository is under active development. The QA package is suitable for development and
> device regression; a distributable package must be produced by the signed release workflow
> described in [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## Current scope

- Subscription import, update, deletion, and metadata display.
- Standalone SOCKS, HTTP, SSH, Shadowsocks, VMess, Trojan, VLESS, AnyTLS, ShadowTLS, TUIC,
  Hysteria, WireGuard, Trojan-Go, NaiveProxy, and Mieru profiles.
- Explicit group and node selection. The selected node is used by default; users may opt in to
  automatic node selection.
- VPN/TUN, selected-app routing, and Android system integration.
- Rule assets, DNS configuration, connection testing, notifications, quick settings, and
  subscription background updates.
- A focused `arm64-v8a` distribution for the current Android release workflow.

The current product target is Android. The app supports Android API 21 and later, while release
acceptance is performed on supported Android devices using the checklist in
[RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## Technology

- Android SDK and Kotlin
- Gradle and Android Gradle Plugin
- Go data core for deterministic subscription, URL, rule-integrity, logging, and latency decisions
- Go-based sing-box integration
- sing-box 1.13.14 with the committed Neko Android integration

Android UI, lifecycle, and Room remain in Kotlin. Java is limited to the reviewed persisted-model
compatibility ABI, and Rust is not part of the source or packaged runtime; see
[LANGUAGE_STRATEGY.md](LANGUAGE_STRATEGY.md).

## Prerequisites

- Android API 21 or later for the application target.
- JDK 17.
- Android SDK 35, Build Tools 35.0.1, and NDK 25.0.8775105.
- Go 1.26.5.

## Development

Prepare the native core and bundled rule assets:

```bash
./run lib core
```

Build and install the QA variant locally:

```bash
./gradlew --no-daemon --max-workers=1 --no-parallel \
  app:testQaUnitTest app:lintQa app:assembleQa
```

QA builds use a separate application ID and the Android debug certificate. They are intended for
development and device regression, not for distribution as a formal release.

## Verification

Run the unit tests and lint checks for the variant under test. For a formal package, use the
protected release configuration and complete the device checks in
[RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md):

```bash
./gradlew --no-daemon --max-workers=1 --no-parallel \
  app:verifyOfficialReleaseReadiness \
  app:testReleaseUnitTest app:lintRelease app:assembleRelease
```

The signed release workflow can be started manually with `build_type=release`. Every push to
`main` produces an `arm64-v8a` QA APK as a GitHub prerelease.

## Project layout

```text
app/src/main/            Android UI, services, profiles, rules, and resources
app/src/test/            JVM unit tests
app/src/androidTest/     device and instrumentation tests
libcore/                 Go bindings and sing-box integration
libcore/data_core.go     Go subscription and latency planning
scripts/                 Native build and VPS-to-GitHub publishing helpers
.github/workflows/       QA and formal release automation
```

## Documentation

- [Release checklist](RELEASE_CHECKLIST.md)
- [Build and signing guide](BUILDING.md)
- [Security policy](SECURITY.md)
- [Contributing guide](CONTRIBUTING.md)
- [Language ownership and migration boundary](LANGUAGE_STRATEGY.md)
- [Mac product reference](https://github.com/killertop/NekoPilot-Mac)

Source publication is routed through the VPS bare repository with
[scripts/post-receive-github-sync.sh](scripts/post-receive-github-sync.sh), then pushed by the
VPS to this project's GitHub repository.

## Project links

- [Source code](https://github.com/killertop/NekoPilot-Android)
- [Releases](https://github.com/killertop/NekoPilot-Android/releases)
- [Issues](https://github.com/killertop/NekoPilot-Android/issues)
- [Mac companion project](https://github.com/killertop/NekoPilot-Mac)

## License and source notices

NekoPilot for Android is distributed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
The project incorporates and credits [SagerNet/sing-box](https://github.com/SagerNet/sing-box),
[shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android), and
[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid). See the
repository license and source notices for the applicable terms.
