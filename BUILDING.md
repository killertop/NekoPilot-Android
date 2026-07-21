# Building NekoPilot locally

## Requirements

- JDK 17
- Android SDK 35 and Build Tools 35.0.1
- Go only when rebuilding the pinned official libbox AAR

Create an ignored `local.properties` containing `sdk.dir=/absolute/path/to/Android/sdk`.

## Build

```bash
SING_BOX_SOURCE=/path/to/sing-box-1.14.0-alpha.48 ./scripts/build-official-libbox.sh
./gradlew --no-daemon --max-workers=1 --no-parallel app:testDebugUnitTest app:lintDebug app:assembleDebug
```

The app has one distribution and produces one optimized `arm64-v8a` APK per build type.

The build packages the official sing-box `experimental/libbox` AAR at the pinned version. The
repository does not contain a second product-specific Go runtime or JNI bridge.

If the Go module proxy is unreachable while rebuilding libbox, select an accessible proxy only for
that invocation. Do not commit a machine- or region-specific proxy setting.

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
