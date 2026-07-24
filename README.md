# NekoPilot for Android

**简体中文 / English**

[官方网站 / Official website](https://nekopilot-official.sturdy-joy-3290.chatgpt.site) · [下载 / Releases](https://github.com/killertop/NekoPilot-Android/releases) · [问题反馈 / Issues](https://github.com/killertop/NekoPilot-Android/issues)

NekoPilot 是一款简洁可控的 Android 代理客户端：导入订阅或节点，按延迟选择并连接，通过 VPN/TUN、分应用代理、DNS 与规则集管理日常网络。

NekoPilot is a simple, controllable Android proxy client. Import subscriptions or nodes, choose by latency, connect through VPN/TUN, and manage per-app routing, DNS, and rule sets in one place.

> 本仓库处于持续开发中。请从 [Releases](https://github.com/killertop/NekoPilot-Android/releases) 获取可安装包；QA 包仅用于开发与回归验证。
>
> This repository is under active development. Get installable packages from [Releases](https://github.com/killertop/NekoPilot-Android/releases); QA packages are for development and regression testing only.

## 核心能力

- 导入、更新和删除订阅；也支持导入单个节点链接。
- 节点列表默认按测速延迟排序；选择哪个节点，就连接哪个节点。
- 支持常见 sing-box 协议，包括 Shadowsocks、VMess、VLESS、Trojan、AnyTLS、TUIC、Hysteria、WireGuard、SOCKS 和 HTTP。
- 提供 Android VPN/TUN、仅所选应用代理、DNS 与中国域名/中国 IP 规则集更新。
- 提供节点测速、连接状态与失败原因提示、订阅更新和局域网共享。
- 当前正式分发仅提供优化的 `arm64-v8a` APK。

## Key capabilities

- Import, update, and delete subscriptions; individual node links are supported too.
- Nodes are ordered by measured latency by default: the selected node is the node that connects.
- Supports common sing-box protocols including Shadowsocks, VMess, VLESS, Trojan, AnyTLS, TUIC, Hysteria, WireGuard, SOCKS, and HTTP.
- Provides Android VPN/TUN, selected-app proxying, DNS, and updatable China-domain/China-IP rule sets.
- Includes node speed tests, connection state and failure feedback, subscription refresh, and LAN sharing.
- Formal distribution currently provides one optimized `arm64-v8a` APK.

## 技术与边界 / Architecture

- Android UI、生命周期、权限、Room 数据库和产品策略由 Kotlin 负责。
  Android UI, lifecycle, permissions, Room, and product policy are implemented in Kotlin.
- 代理数据面使用固定版本的官方 sing-box `experimental/libbox` AAR。
  The proxy data plane uses the pinned official sing-box `experimental/libbox` AAR.
- 本仓库不包含业务 Go、Rust、Java 或自定义 JNI 运行时。
  This repository contains no product Go, Rust, Java, or custom JNI runtime.
- UI 不直接调用命令行或原生接口；Kotlin Service 负责 Android VPN、TUN 文件描述符、分应用代理与生命周期集成。
  UI never calls a CLI or native APIs directly; the Kotlin service layer owns Android VPN, TUN file descriptors, per-app routing, and lifecycle integration.

详见 / Details: [语言边界与架构说明 / Language strategy](LANGUAGE_STRATEGY.md)

## 快速开始 / Quick start

### 使用应用 / Use the app

1. 从 [Releases](https://github.com/killertop/NekoPilot-Android/releases) 下载最新 APK。
   Download the latest APK from [Releases](https://github.com/killertop/NekoPilot-Android/releases).
2. 导入订阅链接或节点链接。
   Import a subscription link or a node link.
3. 选择节点，点击连接；首次连接时按 Android 系统提示授予 VPN 权限。
   Select a node and tap Connect; on first use, grant the Android VPN permission when prompted.

### 本地构建 / Build locally

环境要求：JDK 17、Android SDK 35、Build Tools 35.0.1。仅在重建官方 libbox AAR 时需要 Go。
Requirements: JDK 17, Android SDK 35, and Build Tools 35.0.1. Go is needed only to rebuild the official libbox AAR.

```bash
./scripts/build-official-libbox.sh
./gradlew --no-daemon --max-workers=1 --no-parallel \
  app:testQaUnitTest app:lintQa app:assembleQa
```

QA 包使用独立 application ID 与 Android debug 证书，不用于正式分发。
The QA package uses a separate application ID and the Android debug certificate; it is not a formal distribution build.

## 项目结构 / Project layout

```text
app/src/main/            Android UI, services, profiles, rules, and resources / Android 界面、服务、节点、规则和资源
app/src/test/            JVM unit tests / JVM 单元测试
app/src/androidTest/     Device and instrumentation tests / 设备与 instrumentation 测试
app/libs/libbox.aar      Official sing-box libbox runtime / 官方 sing-box libbox 运行时
scripts/                 Native build and VPS publishing helpers / 原生构建与 VPS 发布辅助脚本
.github/workflows/       QA and formal-release automation / QA 与正式发布自动化
```

## 文档与链接 / Documentation and links

- [官方网站 / Official website](https://nekopilot-official.sturdy-joy-3290.chatgpt.site)
- [构建与签名 / Build and signing](BUILDING.md)
- [发布检查清单 / Release checklist](RELEASE_CHECKLIST.md)
- [安全策略 / Security policy](SECURITY.md)
- [贡献指南 / Contributing guide](CONTRIBUTING.md)
- [语言边界 / Language strategy](LANGUAGE_STRATEGY.md)
- [Mac 配套项目 / Mac companion project](https://github.com/killertop/NekoPilot-Mac)
- [问题反馈 / Issues](https://github.com/killertop/NekoPilot-Android/issues)

源代码通过 VPS 裸仓库中转后同步至 GitHub。
Source publication is relayed through a VPS bare repository and then synchronized to GitHub.

## 许可证与致谢 / License and acknowledgements

NekoPilot for Android 使用 [GNU GPL v3.0](https://www.gnu.org/licenses/gpl-3.0.html) 发布。项目使用并感谢 [SagerNet/sing-box](https://github.com/SagerNet/sing-box)、[shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android) 与 [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) 等开源项目。

NekoPilot for Android is distributed under the [GNU GPL v3.0](https://www.gnu.org/licenses/gpl-3.0.html). The project uses and acknowledges open-source work including [SagerNet/sing-box](https://github.com/SagerNet/sing-box), [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android), and [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid).
