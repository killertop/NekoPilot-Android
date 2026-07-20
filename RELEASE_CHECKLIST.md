# Release checklist

Official release tasks intentionally stop unless production signing and device validation are
explicitly confirmed. Use the `release` build type for signed builds installed on production
devices. The `qa` build type remains available only for isolated diagnostics; it uses a separate
application ID and the Android debug certificate.

The project has one distribution and produces one `arm64-v8a` APK per build type.

## Device regression

Run these checks on at least one Android 14+ device and one oldest-supported Android device:

- Install the signed release APK both from a clean state and over the previous production build.
- Import a subscription link, QR-code profile, and clipboard profile; verify the first imported
  node becomes selected when no valid selection exists.
- Start and stop the VPN, verify real HTTPS egress, and confirm failures are shown on the selected
  node card.
- Exercise selected-app routing, including shared-UID packages and a secondary/work-profile user.
- Verify automatic node selection never interrupts active TCP, UDP, or QUIC traffic and refreshes
  the home page, notification, and quick-settings tile after switching.
- Verify China-domain and China-IP rule updates, checksum validation, primary-source fallback,
  and live VPN reload after an asset changes.
- Verify node speed testing, subscription refresh, background-run status, update checking,
  notifications, and the quick-settings tile.
- Run `./gradlew app:connectedDebugAndroidTest` with the device attached.

After recording the results, set `DEVICE_REGRESSION_CONFIRMED=true` in the protected release CI
environment (or local properties used only for release signing).

Never store production keystore passwords or the keystore in this repository.

## GitHub Actions formal release

The `Build and publish Android release` workflow publishes a formal signed APK only when it is
manually dispatched with `build_type=release`. Configure these repository Actions secrets before
using that path:

- `LOCAL_PROPERTIES`: base64-encoded release properties containing `KEYSTORE_FILE=.local-signing/nekopilot-release.jks`, `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`, and `DEVICE_REGRESSION_CONFIRMED=true`.
- `RELEASE_KEYSTORE_BASE64`: base64-encoded production keystore file.

Every push to `main` remains a debug-signed QA prerelease. The formal workflow decodes the
keystore only on the ephemeral runner, runs `verifyOfficialReleaseReadiness`, and removes the
keystore after publishing.
