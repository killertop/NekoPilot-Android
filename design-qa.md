# Design QA

- source visual truth path: `design/reference/nekopilot-ui-option-1.png`
- implementation screenshot path: `design/audit/13-home-final-zh.png`
- full-view comparison evidence: `design/audit/14-home-final-comparison.png`
- related screen evidence: `design/audit/flows/05-main-flow-board.png`
- dark-theme evidence: `design/audit/flows/07-home-dark.png`
- viewport: 390 × 844 design viewport; Android emulator normalized to the same aspect ratio at 1080 × 2335
- state: light theme, disconnected, selected node present, zero current traffic

## Full-view comparison

The final home screen preserves the selected visual hierarchy: quiet top bar, dominant connection state, large circular connect control, selected-node card, traffic card, and four-item bottom navigation. The implementation uses the same navy/cyan/light-surface direction and the same content order. Android system bars are included in the implementation capture and were normalized before comparison.

## Focused region comparison

The central status/button region, selected-node card, traffic card, and bottom navigation are large enough to inspect in the combined full-height comparison. Additional crops were not needed. The multi-screen board was inspected separately to verify the node, rule, settings, and advanced-menu surfaces.

## Required fidelity surfaces

- Fonts and typography: Android system sans-serif replaces the concept-rendered font, with matching bold state/title hierarchy and readable 13–30sp scale. Chinese labels do not clip or wrap unexpectedly.
- Spacing and layout rhythm: 24dp page margins, 18–20dp cards, generous section gaps, 48dp minimum controls, and system-bar insets are consistent. Persistent navigation remains visible at the target aspect ratio.
- Colors and visual tokens: navy primary, cyan state accent, pale blue background, white surfaces, and subtle borders map to the visual target. Night tokens preserve semantic contrast.
- Image quality and asset fidelity: the supplied NekoPilot launcher/monochrome assets are reused for the connection control and watermark. No placeholder, emoji, custom inline SVG, or generated substitute remains.
- Copy and content: connection state, privacy note, node selection, traffic labels, and primary navigation are concise and available in Chinese and English.
- Icons and interactions: Material-family project icons are used consistently. Connect, node selection, bottom navigation, settings, drawer, import, and rule/settings entry points were exercised on the emulator.
- Accessibility and responsiveness: system bars no longer overlap content, all primary tap targets are at least 48dp, controls have content descriptions, and light/dark themes maintain readable contrast.

## Comparison history

1. P1 — The first implementation placed the title under the Android 15 status bar. Fixed by applying system-window insets to the dashboard and controlling light/dark system-bar icon appearance through the compatibility controller. Post-fix evidence: `design/audit/13-home-final-zh.png`.
2. P2 — The initial node screen was blank when no node existed. Fixed with a descriptive empty state and a working clipboard-import action. Post-fix behavior was compiled and exercised through node import.
3. P2 — The first traffic summary lacked the selected design's card surface, and the connect control lacked a cyan boundary. Fixed with a bordered traffic card and a cyan connect halo. Post-fix evidence: `design/audit/14-home-final-comparison.png`.
4. P2 — The first comparison used a different language and empty-node state. Fixed by switching the app to Chinese and importing/selecting a realistic test node before recapture.

## Findings

No actionable P0, P1, or P2 differences remain.

Remaining P3 polish:

- The reference shows a measured 42 ms delay and VLESS badge; the implementation correctly shows these only when real node-test data exists.
- The reference traffic card includes decorative arrow circles; the implementation prioritizes values and labels and omits these nonessential decorations.
- Exact font rendering differs slightly because the implementation uses the Android system font for native consistency and package size.

## Primary interactions tested

- Four-tab navigation: passed.
- Settings shortcut and advanced drawer: passed.
- Node deep-link import, node selection, and selected-node reflection on home: passed.
- Connect control opening the Android VPN consent flow: passed.
- Light and dark theme rendering: passed.
- Runtime fatal-error scan during the tested flow: no fatal error found.

final result: passed
