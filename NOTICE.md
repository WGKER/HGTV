# 原生版第三方声明

`core` 中内容请求签名、Spade URL / key 处理、MP4 CENC 处理是以下 GPL-3.0 源码的 Kotlin 移植：

- drpys, `spider/js/红果果[短].js`
- https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
- 本仓库对照源：`third_party/drpys/hongguo.cjs`，取回于 2026-09-21。
- 本版改动：完整 Kotlin 实现；签名只选择已验证的 hash branch 0；加密媒体通过 Media3 DataSource 直接读取；增加样本边界检查、子样本处理和 AVC 原始编码恢复。

本仓库的 Android TV 原生应用源码和派生实现以 GPL-3.0-only 发布，许可证全文见 `LICENSE`
（与仓库根目录 `LICENSE` 同一份文本）。源码仓库：https://github.com/pan8664716/hongguoTV ，
发布 APK 时同时提供对应源码、Gradle 包装器和构建说明。APK 不包含 Node、React Native、JavaScript 引擎或该 JavaScript 源文件。`tools/signing-oracle.cjs` 仅为开发期生成对照测试数据的工具，不参与应用构建或运行。

其他依赖：

- ZXing core（本机生成手机输入与更新二维码）：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- Bouncy Castle SM3：https://github.com/bcgit/bc-java — MIT
- jsoup（仅解析官网 HTML，不执行网页脚本）：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0
- Microsoft Fluent Emoji（弹幕表情图，`app/src/main/assets/danmaku_emoji/`，已转为 128px WebP）：
  https://github.com/microsoft/fluentui-emoji — MIT，许可证全文随图放在
  `app/src/main/assets/danmaku_emoji/LICENSE.txt`

应用图标和 TV banner 沿用本仓库素材。这里的应用不是内容平台官方客户端。

应用内「设置 → 开源许可」弹窗展示的就是本文件的信息摘要：GPL-3.0-only 的许可范围、
源码仓库 `pan8664716/hongguoTV`、上面的第三方组件清单，
以及「本机记录只保存在这台设备上」这一条边界。改这里时同步改 `MainActivity.licenseText()`。
