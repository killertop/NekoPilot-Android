# NekoPilot language strategy

## Decision

- Keep Android UI, lifecycle, Room, permissions, and settings in Kotlin.
- Keep sing-box integration, TUN/data-plane work, protocol handling, TLS, and cryptography in Go.
- Do not introduce Rust yet. A third runtime would increase JNI, ABI, binary-size, and maintenance cost without measured benefit.
- Reduce the remaining Java opportunistically when a file is already being changed and parity tests exist; do not perform a mechanical whole-project rewrite.

Current physical source size is approximately 22.6k Kotlin, 7.3k Java, and 10.9k Go lines. About 4.65k Java lines are generated sing-box option bindings, so rewriting them would add maintenance cost without improving the data plane. There is no Rust source.

## Concrete migration map

| Area | Decision | Reason |
|---|---|---|
| Android activities, services, permissions, Room, preferences | Kotlin | Direct Android APIs and lifecycle ownership; JNI would make these paths slower and harder to verify. |
| `SingBoxOptions.java` generated bindings and protocol bean DTOs | Keep generated Java; use Kotlin for newly hand-written Android models | These are serialization/ABI shapes, not performance hotspots. Mechanical conversion has no runtime payoff. |
| sing-box, TUN, TLS/ECH, protocol transport, native HTTP/STUN/procfs | Go | Already inside the native Go boundary; this is the unified high-throughput data plane. |
| `ConfigBuilder.kt` orchestration | Keep Kotlin for Room/model loading; benchmark pure config transformation separately | Moving database-bound orchestration through JNI would add copying and failure modes. A pure transformation function may move to Go only after parity fixtures. |
| `RawUpdater.kt`, `V2RayFmt.kt`, subscription decoding/deduplication | First Go migration candidates after benchmarks | They process potentially large, untrusted batches and can be isolated behind byte/string-in, result/error-out APIs. |
| YAML, WireGuard INI, backup validation | Keep bounded Kotlin implementations now | These run at import time, are covered by Android tests, and are not steady-state throughput paths. |
| Rust | No current candidate approved | Go already owns the native hot path. Rust is considered only for a measured isolated hotspot where it beats the Go baseline after JNI and APK-size costs. |

The target is therefore two maintained languages for hand-written production code: Kotlin for the Android control plane and Go for the native data plane. Generated Java remains an implementation artifact, not a third design language.

## Performance priorities

Measure before moving code. The first benchmark targets are subscription parsing, large rule/profile imports, config generation, startup-to-connected latency, steady-state memory, and VPN throughput/CPU. UI and Android lifecycle code are not migration candidates.

Rust is allowed only for an isolated parser or a demonstrated CPU/memory hot path, behind `-PnekopilotRust=true`, after:

1. Kotlin/Go parity fixtures pass.
2. Benchmarks show a meaningful device-level gain after JNI overhead.
3. `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` packaging is verified.
4. Fuzzing and malformed-input tests pass.
5. APK size and startup regressions are recorded.

Protocol, TUN, authentication, and cryptographic code must not be ported merely for language consistency. Keeping those paths aligned with sing-box's Go implementation is the safer and more unified boundary.
