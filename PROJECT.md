# NekoPilot 项目状态 / Project status

## 产品目标 / Product goal

NekoPilot 是一个简约、可控的 Android 代理客户端：导入节点或机场订阅、选择一个节点、连接，并获得清晰的连接状态与测速反馈。
NekoPilot is a minimal, controllable Android proxy client: import a node or subscription, choose one node, connect, and receive clear connection and speed-test feedback.

## 当前技术边界 / Current technical boundary

- Android 产品层由 Kotlin 实现。 / The Android product layer is Kotlin.
- 代理核心使用官方 sing-box libbox。 / The proxy core is official sing-box libbox.
- 不引入业务 Go、Rust、Java 或自定义 JNI 运行时。 / No product Go, Rust, Java, or custom JNI runtime is included.
- 正式分发为一个优化的 `arm64-v8a` APK。 / Formal distribution is one optimized `arm64-v8a` APK.

## 交付与验证 / Delivery and verification

每次代码变更先通过相关单测、Lint 与设备回归，再生成正式签名 APK。源代码经 VPS 中转同步到 GitHub；发布状态以 GitHub Actions 和 Releases 为准。
Each code change is verified with relevant unit tests, lint, and device regression before a signed APK is produced. Source is synchronized to GitHub through a VPS relay; GitHub Actions and Releases are the source of truth for publication state.

相关说明： [README](README.md) · [构建 / Building](BUILDING.md) · [发布 / Release](RELEASE_CHECKLIST.md) · [架构 / Architecture](LANGUAGE_STRATEGY.md)
