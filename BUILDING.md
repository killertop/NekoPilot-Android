# 构建 NekoPilot / Building NekoPilot

## 环境要求 / Requirements

- JDK 17
- Android SDK 35 与 Build Tools 35.0.1 / Android SDK 35 and Build Tools 35.0.1
- 仅重建固定版本的官方 libbox AAR 时需要 Go。 / Go is required only to rebuild the pinned official libbox AAR.

创建已忽略的 `local.properties`，其中包含 `sdk.dir=/absolute/path/to/Android/sdk`。
Create an ignored `local.properties` containing `sdk.dir=/absolute/path/to/Android/sdk`.

## 本地构建 / Local build

```bash
SING_BOX_SOURCE=/path/to/sing-box-1.14.0-beta.1 ./scripts/build-official-libbox.sh
./gradlew --no-daemon --max-workers=1 --no-parallel \
  app:testQaUnitTest app:lintQa app:assembleQa
```

每个构建类型只生成一个优化的 `arm64-v8a` APK。
Each build type produces one optimized `arm64-v8a` APK.

版本号固定为三段 `主版本.次版本.修订版`，修订版范围为 `0` 到 `10`。执行 `./scripts/bump-version.sh patch` 时，修订版到 `10` 会自动进位到下一个次版本并归零；也可传入 `minor` 或 `major` 主动进位。
Version names always use three components, `major.minor.patch`, with patch ranging from `0` to `10`. `./scripts/bump-version.sh patch` rolls patch `10` into the next minor version; `minor` and `major` are also supported for explicit bumps.

构建会打包固定版本的官方 sing-box `experimental/libbox` AAR；仓库不包含第二套产品专用 Go 运行时或 JNI 桥接层。
The build packages the pinned official sing-box `experimental/libbox` AAR; this repository has no second product-specific Go runtime or JNI bridge.

如果重建 libbox 时无法访问 Go module proxy，只能为该次命令选择可访问的代理；不要提交任何机器或地区专用代理设置。
If the Go module proxy is unavailable while rebuilding libbox, choose an accessible proxy only for that invocation; never commit a machine- or region-specific proxy setting.

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
