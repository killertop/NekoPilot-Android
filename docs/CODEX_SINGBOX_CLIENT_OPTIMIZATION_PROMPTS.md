# NekoPilot Android：通往顶级 sing-box 客户端的 Codex 执行提示词

这不是一次“把构建跑绿”的清单。目标是让 Android 端在真实网络、真实 VPN、真实订阅和升级路径中都可预测、可恢复、可诊断。每个提示词都可以单独交给 Codex；执行前必须先重新检查当前工作树，而不是把本文中的结论当成当前事实。

## 总原则：所有阶段都必须遵守

复制下面这段作为每个任务的前缀：

```text
你正在维护 /Users/bob.liu/Documents/nekopilot 的 Android sing-box 客户端。先运行 git status --short --branch、阅读相关源码与现有测试；不要覆盖用户已有的未提交改动，也不要删除 design/audit 下的历史材料。

产品不变量：
1. Android VPN/TUN 与系统代理只能在“新运行时健康”后交接；候选失败必须保留旧核心和旧系统 VPN，期间不能释放系统代理。
2. 任何未支持的协议、传输、规则或配置都必须在导入/预检时明确失败，绝不能静默退化为其他行为。
3. 默认最小暴露面：本地混合代理只绑定回环；若启用 LAN，必须有随机、非空的用户名和密码。
4. 订阅、二维码、剪贴板和重定向是敌对输入；所有网络边界都要限长、校验 scheme、重定向和最终地址。
5. 数据库升级绝不允许以“删除用户数据后继续”为默认恢复策略。
6. 只有真实 Android 设备上的 VPN 建连、流量转发、DNS、断网恢复和 egress 才能证明端到端成功；单元测试、模拟器、APK 构建都不能替代它。

先写出：问题证据（文件:行号）、最小正确设计、失败/回滚语义、测试矩阵。完成后运行与改动匹配的测试，报告已验证、未验证和需要真实设备验证的内容。不要 push 或发布。
```

## 当前轮已执行的高优先级改造

以下是本轮实际落到源码的内容；仍需用真实设备完成端到端验证。

- 协议编译：Naive、ShadowTLS、SSH 进入 Kotlin outbound 配置映射；WireGuard 已迁移为 sing-box endpoint（不再生成已移除的 legacy outbound）；未知 V2Ray transport 改为明确失败，VLESS scheme 解析改为大小写无关。
- 运行时安全：TUN 描述符保留失败会中止本次重载并撤销阶段状态；`addAllowedApplication` / `addDisallowedApplication` 失败不再被吞掉。
- 暴露面：LAN mixed inbound 没有完整用户名/密码时直接拒绝生成配置。
- 规则：启用的用户路由规则现在会编译为 route 与 DNS 规则；指向当前未运行节点的规则会明确失败；按包名的规则先解析为 UID。
- 订阅：HTTPS-only、无嵌入式凭据、禁止私网/保留地址、每跳重定向验证、最大重定向次数、响应大小限制与 DNS 地址检查。
- 数据：移除了 profile DB 的 destructive fallback，恢复 1→9 迁移链并新增 9→10 保数据迁移以及 instrumentation migration test。

这些改动的源码入口主要是：

- `app/src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt`
- `app/src/main/java/io/nekohasekai/sagernet/fmt/KotlinSingBoxConfig.kt`
- `app/src/main/java/io/nekohasekai/sagernet/fmt/KotlinSingBoxOutbound.kt`
- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt`
- `app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt`

## 提示词 0：建立“真实产品”基线和风险账本

```text
执行一次只读的 NekoPilot Android 架构与风险审计。范围包括 app/src/main、app/src/test、app/src/androidTest、buildScript、.github/workflows、app/libs/libbox.aar、app/schemas 和 docs。

请输出一份 docs/audit/YYYY-MM-DD-current-state.md，按 P0/P1/P2/P3 分级，每条都要包含：
- 用户可见影响；
- 证据文件和精确行号；
- 是否已在源码、单元测试、instrumentation、模拟器、真机上验证；
- 最小修复方案及可能的兼容性风险；
- 归属到后续哪个提示词。

必须覆盖：协议/导入、VPN/TUN 重载、DNS/路由、订阅、数据库、UI/无障碍、日志与隐私、性能、构建/供应链、发布和真实设备 QA。不要修改产品逻辑。
```

验收：风险账本不能把“构建成功”写成“真机 VPN 成功”，必须明确当前连接设备和已安装 APK 版本。

## 提示词 1：协议能力矩阵与配置编译器

```text
审计并完善 Kotlin sing-box 配置编译器。以持久化 ProxyEntity 支持的所有类型为输入，以当前 app/libs/libbox.version 对应的官方 sing-box schema 为唯一运行时标准。

工作项：
1. 生成一个协议能力矩阵：导入 URI/二维码、持久化 bean、Kotlin JSON 映射、Libbox.checkConfig、节点测试、真实 egress 六列；任何缺口都不可隐藏。
2. 为 VMess/VLESS/Trojan/HTTP/SOCKS/Shadowsocks/Hysteria/Hysteria2/TUIC/Naive/ShadowTLS/SSH/WireGuard/AnyTLS 分别补 schema fixture 和编译测试。
3. 未实现的 Trojan-Go、Mieru、Chain、Neko、Config 的行为必须可解释：要么实现成标准 sing-box 组合，要么在导入和连接前返回本地化的“当前官方 runtime 不支持”错误；禁止到启动时才抛 Unsupported node type。
4. V2Ray transport 仅允许经过测试的 tcp/ws/http/grpc/httpupgrade；对 kcp/quic 等不兼容类型给出可操作错误，而不是回退 TCP。
5. WireGuard 必须使用当前官方 endpoint schema；legacy outbound 已从 1.13 移除。覆盖单节点、自动 selector、节点测速与 Android VPN 共存，且不得创建第二个 system interface。
6. 校验 endpoint、端口、UUID、密钥、TLS/Reality/ECH、UDP-over-TCP、插件和自定义字段；不要用 localhost 或空值替代损坏输入。

每个协议至少要有：成功 JSON 断言、非法输入失败断言、Libbox.checkConfig（可运行时）和一条导入→持久化→运行配置的集成用例。更新 docs/protocol-support-matrix.md。
```

验收：产品 UI 暴露的每种协议都有明确的“可连接、明确拒绝、或迁移中”状态；不允许有“保存成功但启动时失败”的盲区。官方 schema 参考：[Naive](https://sing-box.sagernet.org/configuration/outbound/naive/)、[ShadowTLS](https://sing-box.sagernet.org/configuration/outbound/shadowtls/)、[SSH](https://sing-box.sagernet.org/configuration/outbound/ssh/)、[WireGuard migration](https://sing-box.sagernet.org/migration/)。

## 提示词 2：候选核心、TUN 与系统 VPN 的事务性重载

```text
把 VpnService 的重载视为一个不可分割的事务，审计并改造 Engine/OfficialLibboxController/VpnService/CorePreflightService/StagedResourceSwap。

必须满足：
1. 新配置在独立 preflight core 中通过 schema、端口绑定、DNS 与 proxy egress 后，才可触碰运行中的 native core。
2. 普通节点切换重用现有 Android TUN；重复/保留 FD 失败时本轮重载立即停止，旧核心仍继续承载流量。
3. 新 native core 启动、健康探测、候选 TUN 发布、旧 core 回收按顺序完成；任一步失败都不得短暂释放 Android 系统 VPN。
4. 变更 per-app include/exclude 不能伪装成热重载。UI 必须展示“需要重新连接”，或者实现可验证的 make-before-break 系统 VPN 交接。
5. addAllowedApplication / addDisallowedApplication、VPN permission、Builder.establish、FD duplication、native 回调、candidate exit、回滚重试每一条失败路径都要有处理。
6. 关闭和恢复期间没有 FD 泄漏、没有重入竞争、没有过期 callback 影响新 session；以 session generation / binder identity 约束回调。

补单元测试覆盖：候选构建失败、preflight egress 失败、保留 FD 失败、native 启动失败、健康探测失败、回滚成功、回滚耗尽、策略变更、并发重载、服务销毁。补 instrumentation 和真机用例：连续重载 100 次、Wi-Fi/蜂窝切换、VPN revoke、后台杀进程恢复。
```

验收：可以从日志和测试中证明“旧系统 VPN 在候选成功前从未被释放”；若没有真机日志，只能标为源码/模拟器验证。

## 提示词 3：路由、DNS、FakeIP、App 规则和 IPv6

```text
审计 RuleEntity、KotlinSingBoxConfig、VpnService、RouteFragment、AppManagerActivity 与 DNS 配置，把用户界面中保存的规则完整编译到运行时。

要求：
1. 规则顺序必须稳定：平台本地名保护、嗅探、用户规则、内置默认规则、最终出口；为每一层解释优先级。
2. 域名（full/domain/regexp/keyword/rule_set）、CIDR/private、目标/源端口、network、protocol、Android app UID、direct/proxy/block/指定节点都要有测试。
3. 指定节点规则只能引用当前实际构建出的 outbound；缺失节点必须在重载前报错，不能默默走默认代理。
4. DNS 规则必须与 route 规则一致，避免“流量直连但 DNS 走代理”或反之；`.local/.lan/home.arpa` 必须保留在物理网络。
5. IPv4、IPv6、双栈、NAT64、私人地址、DNS bootstrap、DoH bootstrap、FakeIP/嗅探策略都要有明确策略和可切换用户选项，不能把地区策略硬编码成唯一模式。
6. rule-set 的下载/解压/切换必须是同版本原子事务：校验格式、版本、SHA-256/可信来源，失败保留上一套资产；最终由 Libbox.checkConfig 验证。

补 JSON fixture、规则优先级单测、规则→DNS 对偶测试、真实设备 IPv4/IPv6/DNS leak 测试。把默认中国直连策略与“全局代理/自定义策略”明确呈现给用户。
```

验收：RouteFragment 中每条已启用规则都可以在最终 JSON 中定位到；规则的匹配条件和动作有自动化测试，且真机抓包/egress 可验证。

## 提示词 4：订阅、二维码、剪贴板与输入供应链安全

```text
将所有外部输入按敌对数据处理。审计 RawUpdater、NodeImportCoordinator、ScannerActivity、MainActivity、GroupSettingsActivity、所有 URI parser 与资产更新器。

实现和验证：
1. 订阅只允许 HTTPS；拒绝 URL userinfo、file/jar/data、回环、私网、link-local、ULA、CGNAT、文档/保留/multicast 地址。
2. 初始 URL 与每个重定向都要校验；禁止 HTTPS 降级 HTTP；设置小且可测试的跳转上限；对实际 DNS 结果做重新校验以降低 DNS rebinding 风险。
3. 若请求经本地 VPN proxy，必须说明并测试 CONNECT 场景下目标 DNS 的限制；不能只在 OkHttp 的直连 DNS hook 上做校验。
4. 所有输入都需要字符/字节/节点数量/解压比/嵌套深度限制；错误信息去敏，日志不出现 token、密码、订阅完整 URL 或私钥。
5. 区分“一个 HTTPS 节点链接”和“订阅 URL”，二维码不要把任意 HTTPS 误分类为订阅；scheme 比较必须大小写无关。
6. 订阅部分解析或网络失败时，现有节点和当前运行节点不得被删除；数据库变更需要事务和有界取消语义。
7. 给自定义 rule assets、libbox AAR、配置导入建立 SHA-256/provenance 校验，不信任可变 branch/tag。

写单元/MockWebServer/集成测试覆盖重定向链、DNS private answer、响应膨胀、畸形 base64、重复节点、部分解析、运行节点被订阅移除、错误去敏。记录仍无法在本地 proxy CONNECT 中完全强制的威胁模型。
```

验收：每条网络下载都能追踪为“已校验 URL→已校验 DNS 地址→有界响应→原子应用”，且敏感内容不进入日志/UI。

## 提示词 5：数据库迁移、配置兼容和恢复

```text
审计 Room schema、SagerDatabase、PublicDatabase、Kryo wire format、导入导出和版本升级。

任务：
1. 禁止 profile DB 的 fallbackToDestructiveMigration；恢复并维护所有已发布 schema 到当前版本的迁移图。
2. 为每个版本的 schema 建立 MigrationTestHelper 测试；除了 schema validate，还要插入真实 group/profile/rule/subscription 数据，验证升级后 ID、排序、节点二进制、用户规则、选择状态不丢失。
3. 每次实体变化都同时提交 schema JSON、迁移、迁移测试和向后兼容说明。
4. Kryo bean 的版本号、null/默认字段与 URI parser 的演进必须有 golden fixture；损坏数据采用可恢复的“隔离单条记录 + 明确错误”，不要清库。
5. 多进程 Room/DataStore 读取、选择 CAS、订阅更新与服务重载要有竞争测试；禁止 stale selection 覆盖用户刚刚选择的节点。
6. 增加可选的本地加密/安全备份设计，并先完成密钥丢失、root、adb backup、导出分享的威胁建模。
```

验收：用真实旧 schema 数据库跑到当前版本后，节点、规则、订阅、选择和 app routing 设置可读取且可启动；没有“升级后空白首页”的路径。

## 提示词 6：UI、无障碍、可理解的错误和隐私

```text
审计 Home、ConfigurationFragment、Settings、Profile/Route/Group editor、通知和所有错误展示，目标是首次使用者能在一分钟内完成导入、连接、诊断和恢复。

要求：
1. 连接状态从 Idle/Connecting/Preflighting/Reloading/Connected/Recovering/Error 明确区分；任何“连接中”状态都能看到当前阶段、取消/重试和安全建议。
2. 无障碍：最小 48dp 点击目标；不会每秒朗读流量；按钮有对象名；空状态无重复 accessibility node；TalkBack 可完成导入、选节点、连接、断开、查看错误。
3. 错误分为网络、DNS、认证、TLS、配置、权限、设备限制、订阅与内部错误；UI 只显示去敏后的行动建议，详细诊断需用户主动复制。
4. 本地 LAN 代理密码默认遮挡；显示/复制前二次确认；在包含敏感连接信息的页面评估 FLAG_SECURE；测试 URL token 不出现在 preference summary、通知或日志。
5. 所有文案本地化（英文/简中），无硬编码 Toast；深色模式、字体缩放、横屏、折叠屏、RTL 基础检查。
6. Fragment transaction 不使用 commitAllowingStateLoss 规避生命周期错误；配置变更、进程重建和后台恢复不能产生重复 dialog/重复连接。

补 Espresso/Compose?（依当前技术栈）无障碍检查、截图回归和手工验收脚本。不要把 UI 截图验收冒充真实 VPN 验收。
```

## 提示词 7：性能、电池、网络切换与可观测性

```text
以低耗电和可诊断为目标，审计 WorkManager、DefaultNetworkListener、RuntimeTrafficMonitor、AutoNodeSelector、订阅更新和日志。

具体要求：
1. 连接/重载/测速/订阅/规则资产下载都应有有界并发、取消、超时、退避和网络约束；避免同时重启核心。
2. 对网络身份变化采用 generation/session gate，旧网络 callback 不能影响新连接；Wi-Fi/蜂窝/VPN/无网切换要有指标和回归测试。
3. 流量采样、日志、数据库写入、节点列表刷新不能在主线程或每秒全量查询；用 trace/benchmark 证明首帧、滚动、连接重载和大订阅性能。
4. 自动选节点需要解释候选池、探测 URL、失败证据、冷却/熔断和人工选择优先级；不能因单次抖动频繁切节点。
5. 建立隐私友好的本地诊断包：版本、ABI、core version、匿名错误码、状态机时间线、网络类型；默认不含 URL/token/IP/密钥，用户显式同意才导出。
6. 采用 Macrobenchmark/Baseline Profile、StrictMode、LeakCanary（debug）和 fd/thread 计数测试，重点测 100 次重载与大订阅。
```

## 提示词 8：官方 libbox、构建可复现性与发布供应链

```text
审计 app/libs/libbox.aar、libbox.version、buildScript、Gradle、CI 和 release workflow，把运行时来源和产物来源做成可验证链路。

要求：
1. AAR 必须记录 upstream tag/commit、源码 archive digest、构建命令、Go/NDK/JDK 版本、每 ABI 的 ELF/AAR SHA-256；环境变量指定本地源码时同样校验 commit，不能只检查 go.mod。
2. CI 对 marker、AAR digest、ABI、native symbol、签名、minify/R8、dependency verification、license/SBOM、禁止调试开关做 gate。
3. release tag、VERSION_NAME、VERSION_CODE、APK/AAB manifest、Git commit、changelog 必须互相可追溯；禁止用 nkmr_minify=0 生产发布。
4. 不把 x86 emulator 测试当 arm64 真机 VPN 证据；发布 gate 必须分别记录 emulator、arm64 真机、网络 egress、手工 UI 验收。
5. 审计 QUERY_ALL_PACKAGES、前台服务类型、权限文案、数据安全声明和 Play/F-Droid 分发风险。
6. 将 release artifacts、ProGuard mapping、SBOM、测试报告和设备回归报告作为同一 release manifest 的附件。
```

验收：任何人拿到 release manifest 都可以验证“这个 APK 使用的到底是哪一份官方 libbox、哪条源码、哪次测试”。

## 提示词 9：真实设备 QA、混沌测试与发布判定

```text
制定并执行 Android 真机验收计划。优先连接实体 arm64 手机；若没有设备，只能输出 emulator-only 结论，不能标记 release-ready。

至少覆盖：
1. 首次 VPN permission、连接/断开、锁屏、后台、应用被系统杀死、开机自启。
2. Wi-Fi、蜂窝、热点、网络切换、飞行模式、DNS 失败、代理端口冲突、服务器认证/TLS 失败。
3. IPv4/IPv6/双栈、国内/海外 DNS、.local/.lan、WebSocket/gRPC/TUIC/Hysteria/Naive/SSH/WireGuard 样本（仅使用合法测试端点）。
4. 订阅新增/删除/部分失败、规则更新、App 分流变更、连续 100 次重载、自动切换、手动切换与回滚。
5. 泄漏检查：无 VPN 时直连、VPN 中 IP/DNS、LAN inbound 认证、日志/通知/导出文件敏感信息。
6. 升级路径：从每个最近已发布 schema/APK 逐级升级，核对节点、规则、订阅、选择、VPN 仍可用。

每一项写入可机读报告：设备型号/API/ABI、APK SHA、libbox SHA、网络、步骤、预期、实际、logcat 片段、截图/pcap（脱敏）、结论。失败不得被“后续重试成功”掩盖。
```

## 提示词 10：发布前的独立红队审查

```text
不要修改代码，只做独立 red-team review。假设攻击者能控制订阅、二维码、节点参数、DNS、重定向、局域网、进程时序和旧数据库。审计当前变更与所有 release gate。

输出：
- 可利用路径和前置条件；
- 能否窃取 token、绕过 LAN 认证、诱导私网请求、造成 VPN 短暂断流、丢数据库、泄漏 FD、错误分流；
- 对每条路径给出复现实验或说明为什么不可复现；
- P0/P1 阻断项、可接受风险、下一轮最小修复；
- 不把未跑真机、未跑 migration、未验证 arm64 的项目写为通过。

不要因为范围很大而给笼统建议；必须引用当前文件/行号和证据。
```

## 推荐执行顺序和阶段门禁

| 阶段 | 优先目标 | 不可跳过门禁 |
| --- | --- | --- |
| A | 配置编译、导入、VPN 事务 | 单测 + libbox config check + 重载失败路径 |
| B | 路由/DNS/订阅/数据库 | MockWebServer + migration instrumentation 编译/运行 |
| C | UI/性能/可观测性 | 无障碍与生命周期回归 |
| D | 供应链/发布 | AAR provenance + signed release build |
| E | 真机混沌 QA | arm64 真机 VPN/TUN/DNS/egress 报告 |

若任何阶段没有相应证据，后续阶段可以继续开发，但不能宣称“世界级”或“可发布”。

## 每次 Codex 交付的固定模板

```text
结果：一句话说明本轮真正改变了什么。
证据：修改文件、关键行、测试/构建命令及结果。
失败安全性：候选失败时旧核心、系统 VPN、数据库和用户数据分别会怎样。
未验证：真机、网络、签名、升级、UI 中仍缺什么。
下一步：只列最关键的 1–3 个提示词编号。
```
