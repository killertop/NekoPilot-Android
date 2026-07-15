# Release checklist

Official release tasks intentionally stop unless production signing and device validation are
explicitly confirmed. Local installable builds must use the `qa` build type; QA builds use a
separate application ID and the Android debug certificate, so they cannot be confused with or
upgrade a production installation.

## Device regression

Run these checks on at least one Android 14+ device and one oldest-supported Android device:

- Install the QA APK and launch it from a clean state.
- Import and export a manual backup, including profiles, rules, and settings.
- Start and stop both VPN mode and proxy-only mode.
- Exercise per-app routing with the OSS build.
- Reboot with auto-connect enabled and verify the tunnel reconnects.
- Verify connection testing, subscription refresh, notifications, and the quick-settings tile.
- Run `./gradlew app:connectedOssDebugAndroidTest` with the device attached.

After recording the results, set `DEVICE_REGRESSION_CONFIRMED=true` in the protected release CI
environment (or local properties used only for release signing).

## Google Play

Before building the Play artifact:

- Complete the Play Console VPN service declaration and provide the required demonstration video.
- Declare the `specialUse` foreground-service purpose as a user-controlled local proxy tunnel.
- Ensure the store listing and in-app disclosure explain VPN traffic handling and data use.
- Recheck the Data safety form against the final artifact and dependencies.
- Confirm that the Play manifest does not contain broad package-visibility permissions.

After completing the console work, set `PLAY_POLICY_CONFIRMED=true` in the protected release CI
environment. Never store production keystore passwords or the keystore in this repository.
