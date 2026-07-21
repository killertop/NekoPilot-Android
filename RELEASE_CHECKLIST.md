# 正式发布检查清单 / Release checklist

正式发布任务只有在明确确认生产签名与真机验证后才会继续。`release` 构建类型用于安装到生产设备的正式签名包；`qa` 构建类型使用独立 application ID 与 debug 证书，仅供诊断和回归。
Formal release tasks continue only after production signing and device validation are explicitly confirmed. The `release` build type produces the signed production package; the `qa` build type uses a separate application ID and debug certificate for diagnostics and regression only.

每个构建类型只生成一个 `arm64-v8a` APK。
Each build type produces one `arm64-v8a` APK.

## 真机回归 / Device regression

至少在一台 Android 14+ 设备和一台最低支持版本设备上完成以下检查：
Run the following checks on at least one Android 14+ device and one oldest-supported Android device:

- 全新安装和覆盖上一正式版本安装已签名 APK。
  Install the signed APK both cleanly and over the previous production build.
- 导入订阅、二维码节点和剪贴板节点；当没有有效选择时，确认第一个导入节点会自动选中。
  Import a subscription, QR node, and clipboard node; confirm the first imported node is selected when no valid selection exists.
- 启动和停止 VPN，验证真实 HTTPS 出网，并确认所选节点卡片显示失败原因。
  Start and stop VPN, verify real HTTPS egress, and confirm failures are shown on the selected node card.
- 验证分应用代理，包括 shared-UID 与工作资料场景。
  Exercise selected-app proxying, including shared-UID and work-profile scenarios.
- 验证中国域名与中国 IP 规则更新、校验、备用源与热重载。
  Verify China-domain and China-IP rule updates, validation, fallback sources, and live reload.
- 验证节点测速、订阅更新、后台运行、更新检查与快捷设置入口。
  Verify node speed tests, subscription refresh, background operation, update checks, and the quick-settings entry.
- 连接设备后运行 `./gradlew app:connectedDebugAndroidTest`。
  Run `./gradlew app:connectedDebugAndroidTest` with the device attached.

记录结果后，在受保护的发布 CI 环境或仅供本地签名使用的配置中设置 `DEVICE_REGRESSION_CONFIRMED=true`。绝不提交正式密钥或口令。
After recording results, set `DEVICE_REGRESSION_CONFIRMED=true` in the protected release CI environment or local-only signing configuration. Never commit a production keystore or its passwords.

## GitHub Actions 正式发布 / GitHub Actions formal release

`Build and publish Android release` 工作流只有在手动选择 `build_type=release` 时才发布正式签名 APK。使用前需配置以下仓库 Actions secrets：
The `Build and publish Android release` workflow publishes a formally signed APK only when manually dispatched with `build_type=release`. Configure these repository Actions secrets first:

- `LOCAL_PROPERTIES`: base64 编码的正式发布配置，包含 `KEYSTORE_FILE=.local-signing/nekopilot-release.jks`、`KEYSTORE_PASS`、`ALIAS_NAME`、`ALIAS_PASS` 与 `DEVICE_REGRESSION_CONFIRMED=true`。
  Base64-encoded release properties containing `KEYSTORE_FILE=.local-signing/nekopilot-release.jks`, `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`, and `DEVICE_REGRESSION_CONFIRMED=true`.
- `RELEASE_KEYSTORE_BASE64`: base64 编码的正式签名文件。
  Base64-encoded production keystore file.

每次推送 `main` 仍会生成 debug 签名的 QA 预发布包。正式工作流仅在临时 runner 上解码密钥、执行发布检查，并在发布后移除密钥文件。
Every push to `main` still produces a debug-signed QA prerelease. The formal workflow decodes the keystore only on an ephemeral runner, runs release checks, and removes the keystore afterward.
