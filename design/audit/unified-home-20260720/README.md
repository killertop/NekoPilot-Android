# NekoPilot unified home audit — 2026-07-20

## Scope

- Remove launcher long-press shortcuts.
- Present standalone and subscription nodes in one home list.
- Keep subscription updating without exposing group management.
- Reorder the list as latency results arrive.
- Simplify server configuration while preserving protocol-specific values.

## Flow review

| Flow | Result | Evidence |
| --- | --- | --- |
| Home with multiple sources | Healthy — one list, no source tabs | `01-unified-home.png` |
| SOCKS server configuration | Healthy — basic fields first; rare UDP-over-TCP is behind Advanced options | `02-socks-config.png` |
| VLESS server configuration | Healthy — basic identity, connection choices, then configured TLS details | `03-vless-config.png` |
| Subscription update | Healthy — top-right menu says “更新机场订阅”; internal source metadata is retained | Maestro `home-unified-list.yaml` |
| Live latency ordering | Healthy — each successful result reorders immediately, lowest latency first | `HomeGroupListPersistenceTest.latencyResultsReorderUnifiedListImmediately` |
| Launcher long-press | Healthy — APK manifest and Android ShortcutService contain no NekoPilot shortcuts | manifest/ShortcutService verification |

## Verification

- Debug and Android-test APKs built successfully.
- Four instrumentation tests passed on Android 15 emulator.
- Maestro end-to-end import/restart flow passed.
- Unit tests and Kotlin compilation passed.
- English and Simplified Chinese resources compile successfully.
