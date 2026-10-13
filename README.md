# 红果 TV（Android TV 原生版）

本仓库只维护 **Android TV 原生客户端**：Kotlin + Android 原生 View + Media3，最低 Android 8.0 / API 26。
安装一个 APK，联网即可浏览与播放，不需要部署 Node、Docker 或填写服务器地址。

- 应用名称：红果 TV 原生版
- 包名：`com.hongguotv.nativeapp`（`minSdk 26` / `targetSdk 36`）
- 当前版本：0.15.2（versionCode 23）
- 产物：`outputs/hongguotv-kotlin-0.15.2-android8.apk`
- 收藏、观看进度与设置只保存在本机，不上传

> 本仓库此前同时包含 React Native 电视版、iOS 工程与 Node/Docker 内容服务；这些平台已移除，只保留 Android TV 原生版。
> 版本历史与详细发布记录见 [CHANGELOG](docs/CHANGELOG.md)。

## 使用

1. 从 [Releases](https://github.com/pan8664716/hongguoTV/releases) 下载最新正式 APK，拷到电视安装，或用 `adb install -r <APK>` 覆盖安装。
   应用内没有检查或下载更新的入口，设置 → 版本信息只读当前版本号。
2. 在电视应用列表打开「红果 TV 原生版」，进入首页或搜索。
3. 首页剧架自上而下是「历史记录 / 我的收藏 / 热门推荐 / 热门发现」，每栏最多十部，没有记录的剧架不占位；
   历史记录、稍后看和续播条目确认即播放，其余条目确认进入详情。行末「查看全部」进集合页，
   热播榜、热门集合页和搜索结果都停在最后一行按下键可以继续向下加载。设置中可选 480P / 720P / 1080P 上限、是否自动连播与弹幕显示方式。

搜索页：结果按「向下自动加载」呈现，没有上一页 / 下一页，滚到底部或焦点走到最后一行就自动接着取。
第 1 页优先用 App 搜索接口，匿名设备拿不到数据时退回官网搜索，之后每轮再从片库目录筛出片名含关键词的剧接在后面。
设置 → 片单与设备 → 「搜索登录态」可选填 App 抓到的口令，填了以后第 1 页起直接用 App 搜索接口。
搜索框里的上下键会把焦点交回页面（上 = 分类开关，下 = 结果第一张卡）。

播放中：确认键暂停 / 播放；左右跳转 10 秒，长按连续跳转；向下打开选集，向上或菜单键打开播放设置
（清晰度 / 倍速 / 画面模式 / 弹幕 / 定时），左右换选项、确认立即生效。返回键先关闭选集或收起进度条与菜单，再退出播放；
从列表直接续播时回原列表，从详情开始播放时回详情。列表恢复原卡片，远端长列表保留追加出来的内容与滚动位置。

## 构建

需要 JDK 17、Android SDK Platform 36 / Build Tools 36.0.0。Gradle 8.14.3 包装器已包含，不需要 npm 或 Node。
设置 `ANDROID_HOME`，或在 `android/local.properties` 写入 `sdk.dir=<SDK 绝对路径>`。

```sh
# 开发包：直接可安装，无需发布私钥
./android/gradlew -p android :core:test :app:lintDebug :app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

正式 APK 使用独立发布密钥，**私钥与口令不入库**。重建发布包时用环境变量，或创建被 Git 忽略的 `keystore.properties`：

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=your-password
keyAlias=your-alias
keyPassword=your-password
```

对应环境变量：`HONGGUOTV_KEYSTORE`、`HONGGUOTV_STORE_PASSWORD`、`HONGGUOTV_KEY_ALIAS`、`HONGGUOTV_KEY_PASSWORD`。
换用别的签名重建后不能覆盖安装官方密钥签名的 APK（卸载会清除本机记录）；更新已安装的正式版应继续使用同一发布密钥。

```sh
./tools/build-kotlin.sh
# 输出：outputs/hongguotv-kotlin-<版本>-android8.apk
```

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
third_party/drpys/      内容请求签名与媒体处理的对照源（GPL-3.0），运行与构建 APK 不需要
README.md NOTICE.md LICENSE
```

仓库根目录只放文档、工具与第三方对照源；可构建的 Gradle 工程全部收在 `android/`。

播放器直接通过 HTTPS 分段拉取数据，经本机 Kotlin 处理后交给 Media3，没有 JS 引擎或常驻内容服务。
仅在打开「手机输入」或「手机备份与恢复」窗口时，APK 临时监听局域网 HTTP，最长 5 分钟，窗口关闭或应用退到后台即停止。
媒体只在内存中处理，不下载整部剧到存储；元数据响应、封面大小、索引数量与播放缓冲均有上限。

```sh
# 可选联网检查：访问真实网站与内容接口，网络变化可能导致失败
./android/gradlew -p android :core:run --args=--comic       # 分类、搜索两页、详情与真实视频分段
./android/gradlew -p android :core:run --args=--ranking     # 榜单首两页及末页
./android/gradlew -p android :core:run --args="--danmaku"   # 弹幕接口联调（可加剧集 ID）
# 重新生成签名对照样本才需要 Node
node tools/signing-oracle.cjs
```

## 文档

- [变更记录](docs/CHANGELOG.md)
- [红果短剧接口参考](docs/HONGGUO_API.md)
- [请求签名算法](docs/APP_SIGNING.md)
- [账号同步接入调查](docs/ACCOUNT_SYNC.md)
- [新拟态界面改版说明](docs/UI_NEUMORPHISM.md) · [界面改版说明](docs/UI_REDESIGN.md)
- [第三方声明](NOTICE.md)

## 验证边界

已进行 JVM 单元测试、Android lint、正式 APK 构建，并在 Android TV API 36 模拟器上做过真实播放与遥控器检查；
各版本的具体结果见 [CHANGELOG](docs/CHANGELOG.md)。最低 API 26 配置与静态检查不等同于实体电视验收，
**Android 8.0 实体电视的芯片解码、音频输出与长期稳定性仍需实机验证**；解码失败可先试 720P，仍失败需要该电视的日志。
实体电视上的弹幕观感（字号、轨道密度、3 米外可读性）、低端 GPU 帧率与长时间观看下的频控表现同样待实机验收。

内容来自第三方接口；接口、签名、内容可用性或地区网络变化可能需要更新 APK。本应用不是内容平台官方客户端。

原生代码按 GPL-3.0-only 发布；派生来源与依赖声明见 [NOTICE.md](NOTICE.md)，完整许可见 [LICENSE](LICENSE)。
