# 语言边界与架构 / Language strategy

## 责任边界 / Ownership boundary

- Android UI、生命周期、权限、Room、设置、导入、订阅更新、规则资产、配置生成和节点测速编排由 Kotlin 实现。
  Android UI, lifecycle, permissions, Room, settings, imports, subscription updates, rule assets, configuration generation, and node-test orchestration are Kotlin.
- 代理数据面使用固定版本、未修改的官方 sing-box `experimental/libbox` AAR。
  The proxy data plane is the pinned, unmodified official sing-box `experimental/libbox` AAR.
- 本仓库没有业务 Go、Rust、Java 源码或自定义 JNI 桥接层。
  This repository has no product Go, Rust, Java source, or custom JNI bridge.

Kotlin 负责产品策略，并仅通过 Android Service 层适配器调用 libbox；UI 不直接调用 CLI 或原生 API。这样可保留 Android VPN 权限、前台服务、TUN 文件描述符、分应用代理和生命周期集成，同时使用上游 Go 内核的官方移动端接口。
Kotlin owns product policy and invokes libbox only from Android service-layer adapters; UI never calls a CLI or native API directly. This retains Android VPN permission, foreground-service, TUN file-descriptor, per-app routing, and lifecycle integration while using the upstream Go core through its supported mobile interface.

`scripts/verify-language-boundaries.sh` 会拒绝业务 Go、Java、Rust、未批准的原生库，以及除固定官方 `libbox.so` 以外的 APK 运行时。
`scripts/verify-language-boundaries.sh` rejects product Go, Java, Rust, unapproved native libraries, and any APK runtime other than the pinned official `libbox.so`.

官方 `CommandServer` 为 Android 平台日志适配保留上游 Clash 状态收集器；NekoPilot 不会暴露 Clash API 监听器、REST 端点或 YACD 资源。
The official `CommandServer` retains the upstream Clash state collector for its Android platform log adapter; NekoPilot exposes no Clash API listener, REST endpoint, or YACD asset.
