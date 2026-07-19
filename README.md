# NekoPilot for Android

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Releases](https://img.shields.io/github/v/release/killertop/NekoPilot-Android?include_prereleases)](https://github.com/killertop/NekoPilot-Android/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0.html)

NekoPilot is an Android proxy client powered by the sing-box core. Its primary workflow is simple: import a configuration, select a node, connect, and see the connection state clearly.

NekoPilot 是一款由 sing-box 内核驱动的 Android 代理客户端，围绕“导入配置、选择节点、连接并确认状态”设计。

## Download / 下载

所有可下载版本都发布在本项目的 GitHub Releases：

[打开 GitHub Releases](https://github.com/killertop/NekoPilot-Android/releases)

每次推送 `main` 都会自动构建 `arm64-v8a` QA APK，并发布为预发布版本。正式版本需要通过正式签名和设备回归检查后手动触发发布。

All downloadable builds are published on the project's [GitHub Releases](https://github.com/killertop/NekoPilot-Android/releases) page. Every push to `main` builds an `arm64-v8a` QA APK as a prerelease. A production-signed release is created by manually dispatching the protected workflow with `build_type=release`.

## Supported protocols / 支持的协议

- SOCKS4 / SOCKS4a / SOCKS5
- HTTP(S)
- SSH
- Shadowsocks
- VMess
- Trojan
- VLESS
- AnyTLS
- ShadowTLS
- TUIC
- Hysteria 1 / 2
- WireGuard
- Trojan-Go
- NaiveProxy
- Mieru

订阅导入支持常见的 Shadowsocks、ClashMeta、v2rayN 和 sing-box outbound 格式；应用侧主要解析节点出站配置。

## Build locally / 本地构建

完整环境要求、原生内核准备和签名说明见 [BUILDING.md](BUILDING.md)。

```bash
./run lib core
./gradlew --no-daemon --max-workers=1 --no-parallel \
  app:testDebugUnitTest app:lintDebug app:assembleDebug
```

本地测试包使用 `qa` 变体；正式包需要通过受保护的签名配置和设备回归检查。

## Project links / 项目链接

- [Source code](https://github.com/killertop/NekoPilot-Android)
- [Releases](https://github.com/killertop/NekoPilot-Android/releases)
- [Issues](https://github.com/killertop/NekoPilot-Android/issues)
- [Build and release checklist](RELEASE_CHECKLIST.md)

## Credits / 致谢

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)

## License / 许可证

NekoPilot is distributed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
