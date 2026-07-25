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
- 协议输入闭环：Hysteria/Hysteria2、TUIC v5 与 AnyTLS 的 URI、Bean 与 Kotlin JSON 映射已收紧为可保真字段；Hysteria 的端口跳跃、窗口、MTU 与 Gecko 参数已覆盖官方 libbox `checkConfig`。Hysteria 编辑页只允许官方 runtime 支持的 UDP，保存时复用编译器校验；TUIC 先校验端口再校验 v5/UUID/密码。无法由当前官方 runtime 表达的旧 TUIC、FakeTCP/微信视频、Trojan-Go、Mieru、Chain、Neko 会在导入、手动保存、普通选择、快速切换和自动回退时明确拒绝，而非留到 VPN 启动时失败。Custom Config 不是普通受支持节点，外部 raw Config 输入边界仍是下方 P0。
- 运行时安全：TUN 描述符保留失败会中止本次重载并撤销阶段状态；`addAllowedApplication` / `addDisallowedApplication` 失败不再被吞掉。
- 重载真实性：独立 preflight 不再继承用户 `direct` 规则；运行中健康探测走一个仅回环、带认证的专用 mixed inbound，并在 route/DNS 规则最前强制选中代理。这样用户规则不能把失效候选伪装成成功。native 回调的临时 TUN FD 仅在 `startOrReload` 同步调用内存活并在 `finally` 关闭，避免每次成功重载线性泄漏；首次 native start 与 `requestClose()` 并发时，迟到返回的 service 也会被再次关闭，避免遗留 core/listener。
- 重载状态一致性：完整重载会先冻结并记录旧 selector 的实际 `selectorTag + nodeTag`。若候选已触碰 native 后失败，LKG 旧 JSON 重建完成后必须重新选择这一个冻结节点，再做健康检查和状态发布；恢复选择失败会让 LKG 重试/失败，不能留下“界面显示 B、实际出口为 A”的状态错位。
- 分应用策略：include/exclude 改动会先持久化，再发送独立的 VPN policy 请求；已连接时仅当标准化后的 package 列表确实变化才按既有状态机受控 stop/start，非法/空选择保留现有 VPN。Android 没有原子更新 `VpnService.Builder` package policy 的 API，因此这仍是明确可见的短暂重连，不能写成无断流热重载。
- 暴露面：LAN mixed inbound 没有完整用户名/密码时直接拒绝生成配置。
- 规则：启用的用户路由规则现在会编译为 route 与 DNS 规则；指向当前未运行节点的规则会明确失败；按包名的规则先解析为 UID。中国域名/IP 直连默认项也只以已启用的数据库规则为准，因此可分别关闭，不能再被 JSON 编译器强行注入。
- 规则资产事务：每次启动/重载会在与更新器共用的跨进程锁下，把两份 SRS 复制为私有、内容寻址、不可变快照；`Libbox.checkConfig`、独立 preflight 和 live start 全部使用同一个快照目录。损坏/混代源文件不能发布快照。内置 SRS 压缩包及 SHA-256 sidecar 已改为源码锁定、构建离线校验，Gradle 不再从可变 branch/CDN 自动下载；但运行时 OTA 下载来源的签名/provenance 仍未关闭，不能把“快照”或“构建锁定”误写成“运行时供应链校验”。
- 订阅：HTTPS-only、无嵌入式凭据、禁止私网/保留地址、每跳重定向验证、最大重定向次数、响应大小限制与 DNS 地址检查；来自分享链接的订阅会强制清除 `forceResolve`，不能让外部输入指定本机直连 DNS 解析策略。外部 raw `sn://config`、二维码、剪贴板和普通深链均会在解析边界拒绝；订阅内的 raw Config 只会被跳过并保留可安全导入的节点。
- 解析预算：订阅/二维码/深链会先限制 UTF-8 输入字节、物理行/候选数、单链接大小与 Base64/VMess 解码大小；失败不会先建库或删除旧节点。
- 发布 QA：release/QA 强制启用 R8 与资源压缩，`nkmr_minify=0` 被正式任务拒绝；CI 和本地 QA 运行压缩后的 instrumentation，而不是把 Debug 绿色当发布证据。
- 数据：移除了 profile DB 的 destructive fallback，恢复 1→9 迁移链并新增 9→10 保数据迁移以及 instrumentation migration test。

## 本轮已确认、但尚未用 Kotlin 层“假修复”的架构风险

下列问题必须在后续提示词中继续处理；它们不能被“preflight 成功”“VPN 仍注册”或模拟器 `checkConfig` 掩盖：

1. 当前官方 libbox 的 live `startOrReload` 是 break-before-make：旧 native instance 会先关闭，再创建替代实例。独立 preflight 可以避免无效配置过早触碰旧 core，但不能证明 live 替换后候选失败时的数据面不断流。若产品目标是零中断，需要双 controller/稳定前端或 upstream 的原子 instance 交接；否则 UI、日志和验收必须称为“受控重连/可恢复 reload”。
2. active mixed port 或私有 health port 在 live reload 窗口被其他进程抢占时，当前配置字符串无法原子改端口、重建 LKG 并只在新 endpoint 可用后发布给 Binder/DataStore。此项需要可重建的 runtime blueprint 与端口所有权测试。
3. 修改 per-app include/exclude 改变的是 Android `VpnService.Builder` policy，不能伪装成普通 core reload。本轮已改为明确的“受控重连以应用分应用规则”，并会在无效/空选择时保留旧 VPN；但 Android 没有原子 Builder policy 交接，仍需覆盖真机上的选择、重连、失败、package/UID 变化和持续流量中断边界。
4. rule-set 已按内容快照绑定到“checkConfig → preflight → live start”事务，关闭了本机更新导致的验证 A、启动 B 或两份 `.srs` 混代；但下载仍只验证 SRS 格式，且来源可变、原始目录仍在 external files。后续必须改为签名 manifest、精确 hash、回滚保护与私有可信源，才能关闭供应链风险。

这些改动的源码入口主要是：

- `app/src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt`
- `app/src/main/java/io/nekohasekai/sagernet/bg/AutoNodeSelector.kt`
- `app/src/main/java/io/nekohasekai/sagernet/bg/RuleAssetsUpdater.kt`
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
   - preflight 不能继承会让固定探测 URL 走 `direct` 的用户规则；运行时探测也必须使用独立 inbound，并在 route 与 DNS 上先于用户规则钉住当前候选代理。
   - 先核实当前官方 libbox 是否仍是 break-before-make。若是，不能把 preflight 通过写成“无断流”；要么实现双实例/稳定前端交接，要么把产品语义改为可观测、可恢复的受控重连。
2. 普通节点切换重用现有 Android TUN；重复/保留 FD 失败时本轮重载立即停止，旧核心仍继续承载流量。
3. 新 native core 启动、健康探测、候选 TUN 发布、旧 core 回收按顺序完成；任一步失败都不得短暂释放 Android 系统 VPN。
4. 变更 per-app include/exclude 必须走专用的受控重连并向用户说明短暂中断；不得伪装成普通 core reload。若产品要求无断流，必须实现并验证 make-before-break 系统 VPN 交接。
5. addAllowedApplication / addDisallowedApplication、VPN permission、Builder.establish、FD duplication、native 回调、candidate exit、回滚重试每一条失败路径都要有处理。
6. 完整重载开始后，先冻结实际生效的 selector tag 和 node tag。候选失败而 LKG 重建旧 JSON 时，必须在健康检查和 UI/持久化发布前重选冻结节点；重选失败必须进入恢复重试/失败，不能留下 UI 与实际出口不一致。
7. 关闭和恢复期间没有 FD 泄漏、没有重入竞争、没有过期 callback 影响新 session；native callback 临时 FD 只能持有到同步 `startOrReload` 返回，并以 session generation / binder identity 约束回调。

补单元测试覆盖：候选构建失败、preflight egress 失败、保留 FD 失败、native 启动失败、健康探测失败、回滚成功、回滚耗尽、策略变更、并发重载、服务销毁。补 instrumentation 和真机用例：连续重载 100 次、Wi-Fi/蜂窝切换、VPN revoke、后台杀进程恢复。
```

验收：分别报告“Android VPN 注册是否保留”和“旧 core 数据面是否连续”。只有持续流量的真机测试才能声称后者；没有真机日志时只能标为源码/模拟器验证。

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
8. 外部 URI、二维码、剪贴板和订阅不得静默导入原始 `sn://config` 并原样交给 runtime。若保留该能力，只能通过明确的“受信任本地原始配置”流程，并拒绝非回环/无认证 inbound 与危险 DNS、route、日志设置。
9. 分享订阅是远端输入，不能携带本机 DNS/网络策略；导入时重置 `forceResolve=false`，并覆盖该字段为 true 的旧分享序列化。

写单元/MockWebServer/集成测试覆盖重定向链、DNS private answer、响应膨胀、畸形 base64、重复节点、部分解析、运行节点被订阅移除、原始 Config 拒绝、分享 `forceResolve` 清除、错误去敏。记录仍无法在本地 proxy CONNECT 中完全强制的威胁模型。
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
4. 让 R8 后的 QA/release 派生变体运行 instrumentation；Debug 绿不能替代它。正式发布任务必须硬拒绝任何关闭 minify/resource shrink 的环境变量。
5. 不把 x86 emulator 测试当 arm64 真机 VPN 证据；发布 gate 必须分别记录 emulator、arm64 真机、网络 egress、手工 UI 验收。真机证据必须绑定当前 Git SHA、versionCode、ABI、设备/API、APK SHA-256 与测试结果，不能只用一个布尔 secret 放行。
6. 审计 QUERY_ALL_PACKAGES、前台服务类型、权限文案、数据安全声明和 Play/F-Droid 分发风险；公共 issue 模板不得请求订阅链接、节点、私钥或其他凭据。
7. 将 release artifacts、ProGuard mapping、native symbols、APK manifest、SHA256SUMS、SBOM、JUnit XML 和设备回归报告作为同一 release manifest 的附件；验证工作流与实际发布工作流分离，PR 只读验证不得持有发布写权限。
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

## 2026-07-25 审计后仍未关闭的执行账本

下表来自独立的数据面、输入安全、体验和发布审计。它是下一轮的排序依据，不表示已经实现；每次执行前都必须按本页前缀重新核验当前源码。

| 优先级 | 未关闭风险 | 交给 Codex 的提示词 | 完成判据 |
| --- | --- | --- | --- |
| P0 | 官方 libbox live reload 仍是 break-before-make，LKG 只能重建，不能承诺既有 TCP/UDP 流连续。 | 2 | 用 upstream 原子交接或稳定前端设计解决；否则产品/日志/验收统一称为“受控重连”，并以真机持续流量记录边界。 |
| 已关闭（2026-07-25） | 外部 `sn://config` 可把原始 sing-box JSON 原样交给 runtime，绕过普通节点编译器的 LAN、DNS、route 和日志安全边界。 | 4 | 外部 URI/二维码/剪贴板拒绝 raw Config；订阅仅跳过它并保留安全节点。验证见 `KotlinProfileImportTest` 与 2.3.8 QA instrumentation。 |
| P0 | 规则集快照已防混代；内置构建资产已改为源码锁定和离线 SHA-256 校验，但运行时下载仍只验 SRS 格式，来源是可变 branch/CDN，且源资产位于 external files。 | 3、4、8 | 内置 Ed25519 公钥验证的 manifest、精确 hash、版本单调/回滚保护、私有可信发布目录；失败永久保留旧资产。 |
| 已关闭（2026-07-25） | 正式包可受 `nkmr_minify=0` 降级，CI 只运行 Debug instrumentation，不能证明 R8/QA/release 派生变体。 | 8 | release readiness 硬拒绝禁用压缩；CI 运行 R8 后的 QA instrumentation，且有负向门禁测试。 |
| P0 | 当前没有绑定 APK SHA 的完整 arm64 真机 VPN/TUN/DNS/egress 证据。 | 9 | 证据报告绑定 Git SHA、versionCode、ABI、设备/API、APK SHA、网络和结果；含候选失败、LKG、分应用重连、持续流量。 |
| P1 | 默认网络 callback、platform、core 和 selector 缺统一 generation gate；fallback 注册失败时不会持续广播网络变化。 | 2、7 | 有界、合并的网络事件 actor；close/reload 后旧 generation 不能进入 native；强制 fallback 的 Wi-Fi→蜂窝→断网回归通过。 |
| P1 | native `requestClose()` 是异步 fire-and-forget，预检/销毁没有关闭完成确认。 | 2、7 | 有界 close acknowledgement、超时与 generation 隔离；阻塞 close 后下一次 preflight 可恢复的测试。 |
| 已关闭（2026-07-25） | 订阅节点总量和单条 VMess 预算在解析后才限制，恶意输入可造成内存/CPU 峰值。 | 4 | 解析前施加 UTF-8、行数、64 KiB 链接、Base64/VMess 解码预算；失败不建库、不删旧订阅。 |
| P1 | 发布依赖/原生输入缺 lock/校验，发布后未归档 mapping、symbols、manifest、校验和与 JUnit；公共 issue 模板诱导提交订阅链接。 | 8 | dependency verification/lock、libbox provenance lock、SBOM、release artifacts 和脱敏 issue 模板全部进入只读验证门禁。 |
| P1 | AppManager 每次勾选即保存并触发策略重连，首次推荐也会隐式改变配置，用户缺少待应用/失败反馈。 | 6、9 | 本地暂存、变更计数、应用/放弃、失败重试；覆盖旋转、返回未保存、已连接/未连接和真机反馈。 |
| P1 | 启动失败、后台 Error、QS Tile 与测速最小化缺少清晰的恢复/状态语义。 | 6、7、9 | Home、通知和 Tile 显示可行动的错误；测速状态可见且生命周期语义明确；无障碍/真机验证。 |
| P2 | TalkBack 状态、48dp 点击目标、共享 UID 确认、启动授权失效、搜索空态和大列表焦点稳定性仍待实测。 | 6、9 | Accessibility Scanner、TalkBack、字体缩放、横屏、大列表基准与人工验收记录。 |
| P2 | 没有 Macrobenchmark、Baseline Profile、APK/启动预算和 QA 可导出的本地脱敏诊断缓冲。 | 7、8 | 启动/滚动/100 次重载/大订阅基准以及容量/脱敏/退出原因测试作为 CI artifact。 |

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
