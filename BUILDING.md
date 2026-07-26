# 构建 NekoPilot / Building NekoPilot

## 环境要求 / Requirements

- JDK 17
- Android SDK 35 与 Build Tools 35.0.1 / Android SDK 35 and Build Tools 35.0.1
- 仅重建固定版本的官方 libbox AAR 时需要 Go 与仓库文件 [`.android-ndk-version`](.android-ndk-version) 指定的 Android NDK。 / Go and the Android NDK pinned by [`.android-ndk-version`](.android-ndk-version) are required only to rebuild the pinned official libbox AAR.

创建已忽略的 `local.properties`，其中包含 `sdk.dir=/absolute/path/to/Android/sdk`。
Create an ignored `local.properties` containing `sdk.dir=/absolute/path/to/Android/sdk`.

## 本地构建 / Local build

```bash
# `SING_BOX_SOURCE` 必须是位于固定 commit 的 Git checkout；脚本会拒绝无法验证的目录。
SING_BOX_SOURCE=/path/to/sing-box-1.14.0-beta.1 ./scripts/build-official-libbox.sh
./gradlew --no-daemon --max-workers=1 --no-parallel \
  app:testQaUnitTest app:lintQa app:assembleQa
```

每个构建类型只生成一个优化的 `arm64-v8a` APK。
Each build type produces one optimized `arm64-v8a` APK.

版本号固定为三段 `主版本.次版本.修订版`，修订版范围为 `0` 到 `10`。执行 `./scripts/bump-version.sh patch` 时，修订版到 `10` 会自动进位到下一个次版本并归零，同时递增 Android `VERSION_CODE`；也可传入 `minor` 或 `major` 主动进位。
Version names always use three components, `major.minor.patch`, with patch ranging from `0` to `10`. `./scripts/bump-version.sh patch` rolls patch `10` into the next minor version and increments Android `VERSION_CODE`; `minor` and `major` are also supported for explicit bumps.

构建会打包固定版本的官方 sing-box `experimental/libbox` AAR；仓库不包含第二套产品专用 Go 运行时或 JNI 桥接层。
The build packages the pinned official sing-box `experimental/libbox` AAR; this repository has no second product-specific Go runtime or JNI bridge.

如果重建 libbox 时无法访问 Go module proxy，只能为该次命令选择可访问的代理；不要提交任何机器或地区专用代理设置。
If the Go module proxy is unavailable while rebuilding libbox, choose an accessible proxy only for that invocation; never commit a machine- or region-specific proxy setting.

## 拉取请求 CI / Pull request CI

面向 `main` 的 pull request 由 `Android PR CI` 工作流执行独立的 `Android PR quality gate`。本地可用以下步骤复现其静态检查与 QA 构建部分：
Pull requests targeting `main` run the independent `Android PR CI` workflow and its `Android PR quality gate`. Use the following commands to reproduce its static checks and QA build locally:

```bash
./scripts/verify-language-boundaries.sh
NEKOPILOT_LIBBOX_ABIS=x86_64 ./scripts/build-official-libbox.sh
./gradlew --no-daemon --max-workers=1 --no-parallel \
  -Pnekopilot.abi=x86_64 \
  app:verifyOptimizedDistributionBuildTypes app:connectedQaAndroidTest
NEKOPILOT_LIBBOX_ABIS=arm64-v8a ./scripts/build-official-libbox.sh
./gradlew --no-daemon --max-workers=1 --no-parallel \
  app:testQaUnitTest app:lintQa app:assembleQa
./scripts/verify-language-boundaries.sh --check-apk
```

上述 instrumentation 命令要求已启动并连接 API 35 x86_64 emulator。无论 instrumentation 是否成功，继续其他 QA 构建前都必须执行恢复 arm64-v8a 的命令。该 CI 结果只证明受控 emulator、JVM、Lint、构建及 native packaging 边界，不证明 arm64 真机 VPN/TUN、DNS、真实节点 egress 或人工视觉验收。
The instrumentation command above requires a running, connected API 35 x86_64 emulator. Whether instrumentation succeeds or fails, restore arm64-v8a before continuing with other QA builds. This CI evidence covers only the controlled emulator, JVM tests, Lint, build, and native-packaging boundaries; it does not prove arm64 device VPN/TUN behavior, DNS, real-node egress, or manual visual acceptance.

## 正式签名 / Release signing

绝不能将签名密钥或口令提交到仓库。通过环境变量或已忽略的 `local.properties` 提供以下四项：
Never store a signing key or its passwords in this repository. Supply these four values through environment variables or ignored `local.properties`:

```properties
KEYSTORE_FILE=/absolute/path/to/nekopilot-release.jks
KEYSTORE_PASS=...
ALIAS_NAME=...
ALIAS_PASS=...
```

Debug 构建使用标准 Android debug 身份；长期正式签名需要明确授权并进行安全离线备份。
Debug builds use the standard Android debug identity; creating or using the long-term production identity requires explicit authorization and a secure offline backup.
