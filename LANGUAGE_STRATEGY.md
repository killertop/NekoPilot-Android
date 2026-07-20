# NekoPilot language strategy

## Decision

- Keep Android UI, lifecycle, Room, permissions, and settings in Kotlin.
- Keep sing-box integration, TUN/data-plane work, protocol handling, TLS, and cryptography in Go.
- Do not introduce or retain Rust. A third runtime increases JNI, ABI, binary-size, and maintenance cost while duplicating the established Go native boundary.
- Reduce the remaining Java opportunistically when a file is already being changed and parity tests exist; do not perform a mechanical whole-project rewrite.

Current physical production source size is approximately 21.1k Kotlin, 2.4k Java, and 8.0k Go lines. The former generated `SingBoxOptions.java` graph was removed after the Go config compiler made it unreachable. There is no Rust source.

## Concrete migration map

| Area | Decision | Reason |
|---|---|---|
| Android activities, services, permissions, Room, preferences | Kotlin | Direct Android APIs and lifecycle ownership; JNI would make these paths slower and harder to verify. |
| Protocol bean DTOs and `KryoConverters.java` | Keep compatibility Java; use Kotlin for newly hand-written Android models | These preserve persisted serialization/ABI shapes. Mechanical conversion risks existing databases without improving the data plane. |
| sing-box, TUN, TLS/ECH, protocol transport, native HTTP/STUN/procfs | Go | Already inside the native Go boundary; this is the unified high-throughput data plane. |
| Subscription diff and automatic-node candidate policy | Go | Deterministic proxy decisions operate on bounded JSON snapshots and share the existing Go runtime. |
| RecyclerView latency ordering and other presentation state | Kotlin | This is UI policy updated per visual batch; JNI transfer would add latency and allocations without moving proxy work. |
| `ConfigBuilder.kt` orchestration | Keep the Room/model snapshot in Kotlin; compile the Android-neutral snapshot in Go | Database and package lookup stay lifecycle-safe while all sing-box options and route/DNS transformation use the native core. |
| Subscription decoding and protocol-link adapters | Keep Android/model adapters in Kotlin; move bounded batch decisions to Go | URI decoding creates compatibility beans, while matching, diffing, and candidate selection are pure deterministic Go operations. |
| YAML, WireGuard INI, backup validation | Keep bounded Kotlin implementations now | These run at import time, are covered by Android tests, and are not steady-state throughput paths. |
| Rust | Do not use | Go owns proxy-core and pure-data algorithms, avoiding a second native runtime and duplicated JNI/ABI packaging. |

The target is therefore Kotlin for the Android control plane and Go for the native data plane and pure-data decisions. Compatibility Java remains only where persisted protocol/Kryo ABI makes an immediate rewrite unsafe; it is not used for new logic.

## Performance priorities

Measure before moving code. The first benchmark targets are subscription parsing, large rule/profile imports, config generation, startup-to-connected latency, steady-state memory, and VPN throughput/CPU. UI and Android lifecycle code are not migration candidates.

Protocol, TUN, authentication, and cryptographic code must not be ported merely for language consistency. Keeping those paths aligned with sing-box's Go implementation is the safer and more unified boundary.
