# NekoPilot language strategy

## Decision

- Keep Android UI, lifecycle, Room, permissions, and settings in Kotlin.
- Keep sing-box integration, TUN/data-plane work, protocol handling, TLS, and cryptography in Go.
- Do not introduce or retain Rust. A third runtime increases JNI, ABI, binary-size, and maintenance cost while duplicating the established Go native boundary.
- Do not add Java. Android models, Room persistence, lifecycle, settings, and UI belong in Kotlin.

Production source now uses Kotlin and Go only. The generated Java option graph, legacy Java profile beans/converter, redundant Kotlin protocol parsers/encoders, and all Rust code have been removed.

## Concrete migration map

| Area | Decision | Reason |
|---|---|---|
| Android activities, services, permissions, Room, preferences | Kotlin | Direct Android APIs and lifecycle ownership; JNI would make these paths slower and harder to verify. |
| Profile models and Room binary converters | Kotlin | Fresh-install non-null models keep database ownership on Android without Java boxing or historical format branches. |
| sing-box, TUN, TLS/ECH, protocol transport, native HTTP/STUN/procfs | Go | Already inside the native Go boundary; this is the unified high-throughput data plane. |
| Subscription diff and automatic-node candidate policy | Go | Deterministic proxy decisions operate on bounded JSON snapshots and share the existing Go runtime. |
| Subscription response metadata and URL identity | Go, with thin Kotlin adapters | Header validation, display-name parsing, IDN normalization, and subscription deduplication are pure bounded decisions shared by all Android entry points. |
| Base64, numeric IP validation, QR import classification, Release metadata/version comparison | Go, with thin Kotlin adapters | These are bounded platform-neutral transforms; Android retains only UI navigation and prompt rendering. |
| Subscription error redaction and transport-failure classification | Go for sanitization/classification; Kotlin for localized strings | Credentials and technical error semantics are handled once beside the transport core without moving Android resources into JNI. |
| Log redaction and truncation | Go | The native logging boundary sanitizes credentials and bounds entries before they reach the log writer. |
| Rule-asset Release selection, checksum, and database validation | Go | Metadata validation, download integrity, and sing-box database validation form one native trust boundary before Kotlin performs atomic installation. |
| RecyclerView latency ordering and other presentation state | Kotlin | This is UI policy updated per visual batch; JNI transfer would add latency and allocations without moving proxy work. |
| `ConfigBuilder.kt` orchestration | Keep the Room/model snapshot in Kotlin; compile the Android-neutral snapshot in Go | Database and package lookup stay lifecycle-safe while all sing-box options and route/DNS transformation use the native core. |
| Subscription decoding and protocol-link adapters | Parse, normalize, and encode in Go; adapt profile models in Kotlin | One native implementation owns standard URI/document semantics; Android only maps bounded JSON snapshots to Room-owned models. |
| Configuration and subscription documents (Clash YAML, sing-box JSON, WireGuard INI) | Parse and bound in Go; adapt persisted models in Kotlin | Go owns format detection, structural limits, and normalized snapshots while Kotlin keeps Android database ownership. |
| Rust | Do not use | Go owns proxy-core and pure-data algorithms, avoiding a second native runtime and duplicated JNI/ABI packaging. |

The target is Kotlin for the Android control plane and Go for the native data plane and pure-data decisions. Java and Rust are both forbidden by the repository boundary check.

`scripts/verify-language-boundaries.sh` enforces this boundary locally and in CI. It rejects Java, Rust/Cargo sources and native entries, verifies that the Go AAR matches its source fingerprint, and checks packaged APK native libraries. CI additionally runs the Kotlin-to-Go bridge on an Android emulator.

## Performance priorities

Measure before moving code. The first benchmark targets are subscription parsing, large rule/profile imports, config generation, startup-to-connected latency, steady-state memory, and VPN throughput/CPU. UI and Android lifecycle code are not migration candidates.

Protocol, TUN, authentication, and cryptographic code must not be ported merely for language consistency. Keeping those paths aligned with sing-box's Go implementation is the safer and more unified boundary.
