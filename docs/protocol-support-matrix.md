# NekoPilot Android 协议能力矩阵

最后核对：2026-07-25。这里记录的是 **当前源码和本轮验证的边界**，不是产品宣传页。`配置检查` 仅表示
`Libbox.checkConfig` 接受 JSON；它不等于服务端认证、Android VPN/TUN 建连或真实出口成功。

| 协议 / 类型 | 导入与持久化 | Kotlin 配置编译 | 官方 runtime 构建能力 | 配置检查 | 节点测试 / 真实 egress | 当前结论 |
| --- | --- | --- | --- | --- | --- | --- |
| SOCKS、HTTP、Shadowsocks | URI + bean | 已实现 | 已启用 | 已有覆盖 | 本轮未用真实节点复验 | 可用路径，仍需真实节点回归 |
| VMess、VLESS、Trojan | URI + bean | 已实现；未知 V2Ray transport 明确拒绝 | 已启用 | 已有覆盖 | 本轮未用真实节点复验 | 可用路径，需覆盖各 TLS/Reality/transport 组合 |
| Hysteria、Hysteria2、TUIC | URI + bean；Hysteria 端口跳跃/Gecko、手填 UDP-only，TUIC v5/端口严格校验 | 已实现 | `with_quic` | arm64 模拟器官方 libbox 通过 | 无合法真实节点 | runtime JSON 与编辑器边界已验证；仍需真机 egress |
| Naive | bean / 私有序列化；尚无公开 URI 解析 | 已实现 | 本轮新增 `with_naive_outbound` | arm64 模拟器通过 | 无私有测试节点 | runtime 配置已验证；仍需真机 egress |
| ShadowTLS | bean / 私有序列化；尚无公开 URI 解析 | 已实现 | 已启用 | arm64 模拟器通过 | 无私有测试节点 | runtime 配置已验证；仍需真机 egress |
| SSH | bean / 私有序列化；尚无公开 URI 解析 | 已实现 | 已启用 | arm64 模拟器通过 | 无私有测试节点 | runtime 配置已验证；仍需真机 egress |
| WireGuard | bean / 私有序列化 | 已迁移为 1.14 `endpoint`，不再输出已移除的 legacy outbound | `with_wireguard` | arm64 模拟器通过 | 无私有测试节点 | 覆盖单节点与 selector JSON；仍需真机 VPN/TUN 共存和 egress |
| AnyTLS | URI + bean；仅保留可表达参数 | 已实现 | 已启用 | arm64 模拟器官方 libbox 通过 | 无合法真实节点 | runtime JSON 已验证；仍需真机 egress |
| Trojan-Go、Mieru | 旧 bean / 序列化数据可能存在 | 连接预检明确拒绝 | 无对应官方 libbox runtime | 不适用 | 不适用 | 旧 profile 在启动前显示本地化、可行动的拒绝；新导入仍应尽早拒绝 |
| Chain、Neko | 旧内部 bean / 序列化数据可能存在 | 连接预检明确拒绝 | 无对应产品运行时 | 不适用 | 不适用 | 旧 profile 在启动前显示本地化、可行动的拒绝；仍需迁移或在导入期拒绝 |
| Custom Config | 旧 Config bean | 原样交给 libbox 的特殊路径 | 取决于用户 JSON | 由 preflight 负责 | 未验证 | 需要独立的受限模式、导入边界和诊断策略，不能把它等同于受支持节点 |

## 本轮已验证的证据

- Kotlin 单元测试覆盖 Naive、ShadowTLS、SSH、WireGuard endpoint 的 JSON 编译，以及 WireGuard
  被误当 legacy outbound 时的明确失败：
  `KotlinSingBoxOutboundTest`。
- WireGuard endpoint 已同时进入普通运行配置和节点测速配置；selector 可以引用 endpoint tag：
  `KotlinSingBoxConfigTest`。
- Hysteria v1/v2（含端口范围、窗口、MTU、Gecko）和 canonical TUIC v5 已在 API 35 arm64
  模拟器通过 `OfficialLibboxMixedInboundTest` 的官方 libbox `checkConfig`；AnyTLS 也已通过同一
  路径。它们只证明本地 JSON 与运行时 schema 匹配，不证明服务器互通。
- 保存前的 Hysteria/TUIC endpoint 校验、快速切换选择拦截、以及 native first-start/close 竞态分别由
  `HysteriaFmtTest`、`ProfileEndpointValidationTest` 和 `OfficialLibboxControllerTest` 覆盖。它们不替代
  TalkBack/UI 手工验收或真实设备 VPN 流量验证。
- 本轮 arm64 API 35 模拟器完整 instrumentation：86 项、3 项因未提供私有节点/订阅参数跳过、0 项失败。
  `OfficialLibboxMixedInboundTest` 的 Naive、ShadowTLS、SSH、WireGuard endpoint 配置均已由新的官方
  libbox 接受；这仍不等同于实体手机的 VPN/TUN 或服务器 egress。
- arm64 实体机 `25113PN0EC`（Android 16）已通过 `OfficialLibboxMixedInboundTest` 14/14（含完全本机的
  mixed inbound 回环）和 `ProfileSelectionCompatibilityTest` 1/1。一次完整实体机 suite 在第 59 项时
  ADB 断开，因而不能把它记为完整真机回归；也未提供合法节点，所以尚未验证 VPN/TUN 持续流量或服务器 egress。

## 下一轮必须关闭的缺口

1. 在实体 arm64 Android 手机上重新安装本轮 APK，使用合法测试节点完成 Naive、ShadowTLS、SSH、WireGuard
   的本地 mixed inbound、VPN/TUN、DNS 和 egress；模拟器 `checkConfig` 不能替代这些结果。
2. 用合法真实节点完成 Hysteria/Hysteria2/TUIC/AnyTLS 的 TLS、QUIC、UDP、VPN/TUN 与 egress
   验收；当前已有错误输入 fixture 和 arm64 `checkConfig`，但不能替代互通测试。
3. 在 UI 导入和连接预检中处理 Trojan-Go、Mieru、Chain、Neko：要么提供有测试的迁移，要么在保存前
   给出可行动、已本地化的“不受当前官方 runtime 支持”提示。
4. 对 Naive、ShadowTLS、SSH、WireGuard 设计并实现可互操作的 URI/二维码导入，或者在 UI 中清楚标注
   仅能通过受支持的序列化格式导入；不要让用户“保存成功、连接才失败”。
