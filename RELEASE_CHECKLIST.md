# Release checklist

Official release tasks intentionally stop unless production signing and device validation are
explicitly confirmed. Local installable builds must use the `qa` build type; QA builds use a
separate application ID and the Android debug certificate, so they cannot be confused with or
upgrade a production installation.

The project has one distribution and produces one `arm64-v8a` APK per build type.

## Device regression

Run these checks on at least one Android 14+ device and one oldest-supported Android device:

- Install the QA APK and launch it from a clean state.
- Import and export a manual backup, including profiles, rules, and settings.
- Start and stop both VPN mode and proxy-only mode.
- Exercise per-app routing.
- Reboot with auto-connect enabled and verify the tunnel reconnects.
- Verify connection testing, subscription refresh, notifications, and the quick-settings tile.
- Run `./gradlew app:connectedDebugAndroidTest` with the device attached.

After recording the results, set `DEVICE_REGRESSION_CONFIRMED=true` in the protected release CI
environment (or local properties used only for release signing).

Never store production keystore passwords or the keystore in this repository.
