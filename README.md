# 红果 TV（Android TV 原生版）_(hongguoTV)_

红果短剧的非官方 Android TV 客户端：单 APK、Kotlin + Media3，安装后联网即用。

安装一个 APK 即可在 Android TV 上浏览、搜索与播放红果短剧 / 漫剧，不需要部署服务端，也不需要配置服务器地址。
仓库名 `hongguoTV`，应用名「红果 TV 原生版」，包名 `com.hongguotv.nativeapp`；仓库同时维护可构建的 Android 工程与开发期工具。

本仓库只维护 **Android TV 原生客户端**。此前仓库里并存过 React Native 电视版、iOS 工程与 Node / Docker 内容服务，
这些平台与对应的文档、CI 已全部移除，历史版本说明合并进 [变更记录](docs/CHANGELOG.md)。
应用与红果官方无关联，内容接口为非官方实现，详见 [背景](#背景)。

## 目录

- [安全](#安全)
- [背景](#背景)
- [安装](#安装)
- [使用](#使用)
- [界面](#界面)
- [工程结构](#工程结构)
- [开发](#开发)
- [接口](#接口)
- [维护者](#维护者)
- [致谢](#致谢)
- [参与贡献](#参与贡献)
- [许可证](#许可证)

## 安全

- 仓库**不包含**任何发布私钥、签名口令或构建产物：`release.jks`、`keystore.properties`、`local.properties`、
  `outputs/` 下的 APK 与 `build/` 中间产物全部由 [`.gitignore`](.gitignore) 排除，且从未进入过 Git 历史。
- 正式包使用独立发布密钥，签名口令只以环境变量或本机 `keystore.properties` 传入，不写入源码。
- 本机记录（收藏、观看进度、设置）只保存在设备上，不上传；仅在打开「手机输入」或「手机备份与恢复」窗口时，
  APK 才临时监听局域网 HTTP，最长 5 分钟。
- 第三方接口随时可能变化；应用遇到错误会明确显示，不会用假内容顶替。

## 背景

自用的非官方红果短剧客户端，目标是「电视上装一个 APK 就能用」：不需要在电脑或 NAS 上常驻服务，
不需要填服务器地址，遥控器就能完成浏览、搜索、收藏、续播与播放设置。

首版采用 React Native TV + 独立 Node 内容服务，后来重构为 Kotlin + Android 原生 View + Media3 的独立 APK，
播放器直接通过 HTTPS 分段拉取数据并在本机处理，不再依赖 JS 引擎或常驻服务。
重构完成后，React Native 电视版、iOS 工程与 Node / Docker 服务均已移除，仓库只保留 Android TV 原生版。

平台侧只提供客户端接口（官网 SSR + 字节系 App 接口），没有官方开放 API，因此本项目通过逆向整理接入，
请求签名、加密媒体处理与弹幕解析均为自行实现，见 [接口](#接口) 与 [接口参考](docs/HONGGUO_API.md)。

## 安装

从 [Releases](https://github.com/pan8664716/hongguoTV/releases) 下载最新正式 APK，拷到电视安装，或用 ADB 覆盖安装：

```sh
adb install -r hongguotv-kotlin-0.15.2-android8.apk
```

应用内没有检查或下载更新的入口，设置 → 版本信息只读当前版本号；新版 APK 手动覆盖安装，本机收藏、进度与设置保留。

### 依赖

- Android TV 或 Android 8.0（API 26）及以上的设备；目标 API 36。
- APK 不含 JNI 库，Android 会在 ARM32 / ARM64 / x86 / x86_64 上运行；视频解码能力取决于设备。
- 换用别的签名重建后不能覆盖安装官方密钥签名的 APK（卸载会清除本机记录）。

## 使用

电视应用列表打开「红果 TV 原生版」，进入首页或搜索。

遥控器操作：

| 位置 | 按键 | 行为 |
| --- | --- | --- |
| 首页 | 上下 / 左右 | 切换剧架 / 选卡；行末「查看全部」进集合页 |
| 搜索 | 输入后向下 | 结果按「向下自动加载」，滚到底部自动取下一页 |
| 详情 | 确认 | 播放 / 续播；「完整简介」「相关剧集」可展开 |
| 播放中 | 确认 | 暂停 / 播放 |
| 播放中 | 左右 | 跳转 10 秒，长按连续跳转 |
| 播放中 | 向下 | 打开选集面板 |
| 播放中 | 向上 / 菜单键 | 打开播放设置（清晰度 / 倍速 / 画面模式 / 弹幕 / 定时） |
| 播放中 | 返回 | 先关选集或收起控制条，再退出播放 |

首页剧架自上而下是「历史记录 / 我的收藏 / 热门推荐 / 热门发现」，每栏最多十部，没有记录的剧架不占位。
设置中可选 480P / 720P / 1080P 上限、是否自动连播与弹幕显示方式（全屏 / 上方 / 隐藏）。

搜索第 1 页优先用 App 搜索接口，匿名设备拿不到数据时退回官网搜索，之后每轮再从片库目录筛出片名含关键词的剧接在后面。
设置 → 片单与设备 → 「搜索登录态」可选填 App 抓到的口令，填了以后第 1 页起直接用 App 搜索接口。

## 界面

界面为深色新拟态（Neumorphism）风格，含十四套材质主题；播放界面不套新拟态，压在画面上的字与线固定暖白。
离线校对图（非真机截图）在 [`docs/preview/`](docs/preview/)：图标、首页与按钮六态、十四套主题对比。

![首页](docs/preview/home.png)

设计令牌、柔光实现与组件清单见 [新拟态界面改版说明](docs/UI_NEUMORPHISM.md)，排版与焦点规则见 [界面改版说明](docs/UI_REDESIGN.md)。

## 工程结构

```
android/                Gradle 工程根（Android Studio 直接打开这一层）
├─ app/                 原生电视界面、遥控器焦点、本地收藏 / 进度、弹幕绘制层、Media3 播放器
├─ core/                纯 Kotlin 内容列表、搜索、详情、请求签名、弹幕、播放地址解析、MP4 索引与分段
│  └─ src/test/         固定签名对照、NIST AES-CTR 向量、跨样本 / 非对齐分段、AVC 索引恢复与畸形输入校验
├─ gradle/ gradlew       Gradle 8.14.3 包装器
└─ build.gradle.kts     构建脚本与发布签名配置
docs/                   接口参考、请求签名算法、账号同步调查、界面改版说明、变更记录
├─ preview/             离线校对图（图标 / 主题 / 新拟态），非真机截图
tools/                  图标 / 主题 / 新拟态校对脚本、静态校验脚本、构建脚本
README.md NOTICE.md LICENSE
```

仓库根目录只放文档与工具；可构建的 Gradle 工程全部收在 `android/`。

## 开发

需要 JDK 17、Android SDK Platform 36 / Build Tools 36.0.0；Gradle 8.14.3 包装器已随仓库提供。
设置 `ANDROID_HOME`，或在 `android/local.properties` 写入 `sdk.dir=<SDK 绝对路径>`。

```sh
# 开发包：直接可安装，无需发布私钥
./android/gradlew -p android :core:test :app:lintDebug :app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

可选联网检查（访问真实网站与内容接口，网络变化可能导致失败）：

```sh
./android/gradlew -p android :core:run --args=--comic       # 分类、搜索两页、详情与真实视频分段
./android/gradlew -p android :core:run --args=--ranking     # 榜单首两页及末页
./android/gradlew -p android :core:run --args="--danmaku"   # 弹幕接口联调（可加剧集 ID）
```

静态校验与离线校对图：

```sh
python3 tools/verify-button-system.py    # 按钮系统规则（全站唯一实现、三档尺寸、令牌化内边距）
python3 tools/app-icon.py --check        # 矢量 / 位图 / 背景色与脚本是否同源
python3 tools/theme-preview.py           # 十四套主题对比度自检 → docs/preview/themes.png
python3 tools/neumorphism-preview.py     # 首页 / 按钮六态 / 强调色校对 → docs/preview/
```

正式包使用独立发布密钥，**私钥与口令不入库**。重建发布包时用环境变量，或在 `android/keystore.properties` 配置：

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=your-password
keyAlias=your-alias
keyPassword=your-password
```

对应环境变量：`HONGGUOTV_KEYSTORE`、`HONGGUOTV_STORE_PASSWORD`、`HONGGUOTV_KEY_ALIAS`、`HONGGUOTV_KEY_PASSWORD`。

```sh
./tools/build-kotlin.sh
# 输出：outputs/hongguotv-kotlin-<版本>-android8.apk
```

### 验证边界

已进行 JVM 单元测试、Android lint、正式 APK 构建，并在 Android TV API 36 模拟器上做过真实播放与遥控器检查；
各版本的具体结果见 [变更记录](docs/CHANGELOG.md)。最低 API 26 配置与静态检查不等同于实体电视验收，
**Android 8.0 实体电视的芯片解码、音频输出与长期稳定性仍需实机验证**；解码失败可先试 720P，仍失败需要该电视的日志。
实体电视上的弹幕观感（字号、轨道密度、3 米外可读性）、低端 GPU 帧率与长时间观看下的频控表现同样待实机验收。

## 接口

上游分两层，均为客户端接口的逆向整理，不是官方开放 API：

- **官网层**（`hongguoduanju.com`）：Next.js SSR，数据藏在 HTML 的 `_ROUTER_DATA` 里，不需要签名，负责分类、片库目录、榜单、详情与官网搜索。
- **App 层**（`api5-normal-*.fqnovel.com`）：字节系 App 接口，需要一整套设备参数与签名头，负责 App 搜索、播放地址解析与弹幕。

- [红果短剧接口参考](docs/HONGGUO_API.md)：域名与线路、请求参数、响应结构、弹幕窗口、实测结论。
- [请求签名算法](docs/APP_SIGNING.md)：签名输入、hash branch、medusa / gorgon / helios 的构造与验证方式。
- [账号同步接入调查](docs/ACCOUNT_SYNC.md)：为什么当前没有红果账号同步，以及接通需要的平台证据。

## 维护者

[@pan8664716](https://github.com/pan8664716) — 仓库所有者与唯一维护者。

## 致谢

- [drpys](https://github.com/woshishiq1/drpys)（commit `22261ad`，GPL-3.0）：内容请求签名与协议变换的移植来源。
- [hongguo-downloader](https://github.com/zhenyong97/hongguo-downloader)：协议变换的交叉参考。
- [Microsoft Fluent Emoji](https://github.com/microsoft/fluentui-emoji)（MIT）：弹幕表情图。
- AndroidX Media3、OkHttp、ZXing、Bouncy Castle、jsoup 等依赖，完整清单见 [第三方声明](NOTICE.md)。

## 参与贡献

本项目为自用工具，不承诺接受外部 PR；仓库也**不接受**任何形式的功能请求或平台接口变更工单。

- 提问、报 bug 或讨论请开 [Issue](https://github.com/pan8664716/hongguoTV/issues)；提交代码前请先开 Issue 说明意图。
- 提交的补丁需保持现有约定：不引入新的第三方运行时依赖，不在源码或提交里放入私钥、口令与构建产物。
- 请在提交信息里说明改动的动机与验证方式；涉及 UI 的改动请附上 `tools/` 下对应校验脚本的输出。

## 许可证

[GPL-3.0-only](LICENSE) © 2026 pan8664716

派生来源与依赖声明见 [NOTICE.md](NOTICE.md)；第三方部分保留各自许可。
