# NekoPilot for Android

NekoPilot for Android 是一款以 Android 为主要目标的代理客户端，基于 Kotlin、Go 和
sing-box 构建。产品重点是清晰、可控的日常流程：导入订阅或单节点链接，选择节点，按指定模式
连接，并查看实际连接结果。

> 当前仓库仍在持续开发。QA 包用于开发和设备回归；正式分发包必须通过带正式签名的发布流水线
> 生成，详见 [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)。

## 当前范围

- 订阅导入、更新、删除和订阅元信息展示。
- 支持 SOCKS、HTTP、SSH、Shadowsocks、VMess、Trojan、VLESS、AnyTLS、ShadowTLS、TUIC、
  Hysteria、WireGuard、Trojan-Go、NaiveProxy 和 Mieru 配置。
- 明确的分组和节点选择；默认连接所选节点，也可由用户开启“自动选择节点”。
- VPN/TUN、仅所选应用代理和 Android 系统集成。
- 规则资源、DNS 配置、连接测试、通知、快捷设置和订阅后台更新。
- 当前正式发布流水线聚焦 `arm64-v8a` 架构。

当前产品目标是 Android，应用支持 Android API 21 及以上版本；正式验收以
[RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) 中的 Android 设备回归结果为准。

## 技术栈

- Android SDK、Kotlin
- Gradle、Android Gradle Plugin
- 用于确定性订阅差异决策的 Go 数据核心
- Go sing-box 集成
- sing-box 1.13.14 及提交到仓库的 Neko Android 集成

## 环境要求

- Android API 21 或更高版本。
- JDK 17。
- Android SDK 35、Build Tools 35.0.1、NDK 25.0.8775105。
- Go 1.26.5。

## 开发运行

准备原生内核和内置规则资源：

```bash
./run lib core
```

构建本地 QA 包：

```bash
./gradlew --no-daemon --max-workers=1 --no-parallel \
  app:testQaUnitTest app:lintQa app:assembleQa
```

QA 包使用独立的 application ID 和 Android debug 证书，用于开发和设备回归，不作为正式分发包。

## 测试与发布

正式包需要使用受保护的签名配置，并完成 [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) 中的
设备检查：

```bash
./gradlew --no-daemon --max-workers=1 --no-parallel \
  app:verifyOfficialReleaseReadiness \
  app:testReleaseUnitTest app:lintRelease app:assembleRelease
```

正式发布工作流需要手动选择 `build_type=release`。每次推送 `main` 会自动构建一个
`arm64-v8a` QA APK，并发布为 GitHub 预发布版本。

## 目录结构

```text
app/src/main/            Android 界面、服务、配置、规则和资源
app/src/test/            JVM 单元测试
app/src/androidTest/     设备和 instrumentation 测试
libcore/                 Go bindings 和 sing-box 集成
libcore/data_core.go     Go 订阅规划
scripts/                 原生构建和 VPS→GitHub 发布脚本
.github/workflows/       QA 和正式发布自动化
```

## 文档

- [正式发布检查清单](RELEASE_CHECKLIST.md)
- [构建和签名说明](BUILDING.md)
- [安全策略](SECURITY.md)
- [贡献指南](CONTRIBUTING.md)
- [Mac 产品参考](https://github.com/killertop/NekoPilot-Mac)

源码发布默认经由 VPS 裸仓库，再由 VPS 推送到本项目自己的 GitHub 仓库，转推钩子见
[scripts/post-receive-github-sync.sh](scripts/post-receive-github-sync.sh)。

## 项目链接

- [源码仓库](https://github.com/killertop/NekoPilot-Android)
- [GitHub Releases](https://github.com/killertop/NekoPilot-Android/releases)
- [Issues](https://github.com/killertop/NekoPilot-Android/issues)
- [Mac 配套项目](https://github.com/killertop/NekoPilot-Mac)

## 许可证与来源声明

NekoPilot for Android 使用 [GNU GPL v3.0](https://www.gnu.org/licenses/gpl-3.0.html) 发布。
项目集成并致谢 [SagerNet/sing-box](https://github.com/SagerNet/sing-box)、
[shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android) 和
[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)。具体适用条款
请以仓库中的许可证和源代码声明为准。
