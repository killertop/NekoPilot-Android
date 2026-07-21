# Design QA — Complete UI pass

- source visual truth: `design/reference/nekopilot-ui-option-1.png` and pre-migration captures under `design/audit/completeness-20260715/`
- implementation evidence: `design/qa/completeness-pass/`
- combined comparisons: `compare-settings.png`, `compare-editors.png`, `compare-secondary.png`
- viewport: Android emulator, 1080 × 2335
- state: simplified Chinese, light theme, disconnected, one selected Shadowsocks demo profile

## Surfaces reviewed

- Primary flow: home, nodes, rules, settings.
- Shared forms: server/protocol preferences, group editor, route editor, custom config and import dialogs.
- Secondary flow: drawer, group list, log, tools, network test, backup/restore and about.
- System utilities: per-app proxy selector, route assets and QR scanner.

## Fidelity review

- Typography: native Android sans-serif with bold navy titles, muted summaries and monospaced technical data. No inspected Chinese label clips.
- Spacing: consistent page insets, section rhythm, card padding and 48dp-or-larger primary controls.
- Color: navy primary, cyan accent, pale blue background, white surfaces and subtle borders are consistently reused.
- Assets: project launcher/brand assets and existing vector icons are reused; no placeholder artwork remains.
- Copy: Chinese and English resources cover the new states and actions; duplicate navigation and promotion copy were removed.
- Interaction: secondary pages hide the primary bottom navigation, while close/save/delete/filter/update controls retain clear state and content descriptions.

## Comparison history

1. P1 — The first global Preference migration crashed when opening a dropdown because the custom row omitted the required Spinner. Fixed with a Spinner-compatible dropdown layout and re-tested through profile and settings dropdowns.
2. P2 — Secondary destinations retained a selected primary bottom tab. Fixed by hiding bottom navigation and releasing its reserved padding for drawer and standalone destinations.
3. P2 — The log screen initially exposed an unstructured raw stream. Fixed with All, Warnings and Errors filters, readable monospaced output and a warnings-first empty state.
4. P2 — The about page mixed old library cards and wrapped the core version poorly. Rebuilt as native NekoPilot cards and adjusted the technical version treatment.
5. P2 — Settings, route, protocol and group editors visually fell back to legacy Preference rows. Fixed through shared card/category/dropdown/switch/edit/seekbar styles and verified on real screens.

## Evidence

- `02-settings.png` and `04-profile-editor.png`: shared Preference system.
- `03-drawer.png`, `05-log-final.png`, `06-tools.png`, `07-about-final.png`, `08-backup-final.png`: secondary navigation and utilities.
- `09-app-manager.png`, `10-assets.png`, `11-scanner.png`, `12-route-editor.png`: application, asset, scanner and routing flows.
- `13-qa-smoke.png`: final minified QA package launched after installation.

## Findings

No actionable P0, P1 or P2 issue remains. One P3 remains acceptable: the full sing-box feature version string can wrap naturally on narrow devices but stays readable.

## Regression result

- Core navigation and editor entry points: passed.
- Dropdown, switch, search, filter and update controls: passed.
- Runtime fatal-error scan during the final walkthrough: passed.
- Unit tests, Android lint and QA packaging: passed (`testDebugUnitTest`, `lintDebug`, `assembleQa`).

previous result: passed

---

# Design QA — Top connection action revision

- source visual truth: `/Users/bob.liu/.codex/visualizations/2026/07/17/nekopilot-bottom-connect/01-before.png`
- implementation screenshot: `/Users/bob.liu/.codex/visualizations/2026/07/17/nekopilot-top-connect-final/01-idle.png`
- connected-state screenshot: `/Users/bob.liu/.codex/visualizations/2026/07/17/nekopilot-top-connect-final/02-connected.png`
- full-view comparison evidence: `/Users/bob.liu/.codex/visualizations/2026/07/17/nekopilot-top-connect-final/03-comparison-full.png`
- focused header comparison evidence: `/Users/bob.liu/.codex/visualizations/2026/07/17/nekopilot-top-connect-final/04-comparison-header.png`
- viewport: Xiaomi 17, 1220 × 2656 app surface, light theme, simplified Chinese
- state: selected VPS-Trojan profile; disconnected and connected states checked

## Findings

No actionable P0, P1 or P2 mismatch remains.

- Typography: the implementation keeps the existing Android system type scale, weight and truncation behavior; the connection state and profile metadata remain readable.
- Spacing and layout: the circular action is restored to the status-card upper-right, the node summary remains below it, and the bottom bar is again three equal navigation destinations. The 48dp action sits inside a 56dp progress container without clipping.
- Colors and tokens: disconnected navy, connected green, cyan status/progress and pale-blue surfaces reuse the project tokens.
- Image and icon fidelity: the existing Material power and location vector assets are reused at the intended size; no placeholder or generated asset is present.
- Copy and content: the action exposes `连接` and `停止` accessibility descriptions, while the card reports the selected node and connection state.
- Interaction: connect and stop both update the card, button color and accessibility state; the three bottom destinations remain navigation-only.

## Comparison history

1. P1 — The prior bottom-bar action read visually as a fourth destination and made the navigation feel crowded. Fixed by removing the bottom action and restoring the selected top-right card action.
2. Post-fix evidence — The full and focused comparisons show the selected top placement, circular treatment and three-item navigation restored. The live connected capture shows the green stop state and VPN indicator.

## Verification

- QA package, Android lint, unit tests and Android-test Kotlin compilation: passed.
- Real-device install, cold launch, connect, stop and fatal-exception scan: passed.
- Residual evidence limit: this revision verifies the connection-control UI and VPN state transition; it does not claim successful external HTTPS egress through the stored VPS node.

previous result: passed

---

# Design QA — Node sources and import ownership

- source visual truth: supplied NekoPilot macOS node-management screen.
- implementation capture: Android emulator, empty and populated airport-subscription states.
- scope: bottom navigation, source status, add/update/delete actions, and Home-to-Nodes linkage.

## Comparison

- The Android implementation preserves the reference hierarchy: Home / Nodes / Rules / Settings, source cards, node count, last-update status, add and update-all actions, and disclosure into source actions.
- Android system chrome, Material typography, 18 dp card radius, 12 dp page margins, and the established NekoPilot color tokens intentionally replace macOS window chrome.
- Airport deletion is explicit behind the source card, includes node count and an irreversible-action warning, and updates the empty state immediately.
- Empty and populated states fit the device width without clipped text or off-screen actions.
- Add/import actions live only in Nodes; the Home empty state is a direct link to that screen, avoiding two competing import entry points.

## Verification

- Imported a live 33-node airport subscription on the emulator.
- Opened source actions, verified update and delete entries, confirmed deletion, and observed the empty state without a crash.
- Verified that Home has no duplicate add action and Nodes owns the shared QR, clipboard, and airport-link import menu.
- Verified the four-item primary navigation and import-action ownership with Android instrumentation tests.

final result: passed
