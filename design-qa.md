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

final result: passed
