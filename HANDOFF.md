# HANDOFF

## 项目
- 路径：`F:\schoolCompWorks\clone\mobileSrcDownloadplugin`
- 技术栈：Kotlin 2.0.21 + Jetpack Compose (BOM 2024.10.01)，AGP 8.7.3，Gradle 8.9，minSdk 26 / target & compile 34
- 模块：`:app`（UI/悬浮窗/剪贴板）、`:parser`（纯 JVM 解析内核）、`:downloader`（Android Library 下载引擎）
- 版本控制：**尚未初始化 git**（无分支，无提交历史）
- 开发环境：JDK17 `C:\AndroidDev\jdk\jdk-17.0.20.1+1`，SDK `C:\AndroidDev\sdk`，Gradle `C:\AndroidDev\gradle-8.9`；环境变量已写入用户级；统一构建脚本 `C:\AndroidDev\build.bat`（内含 JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME，可带任意 gradle 参数）

## 目标
- 已达成：安卓应用「剪贴板识别 → 解析 → 下载」，悬浮窗毛玻璃弹窗，解析与下载全自研
- 非目标：不内嵌 Python/yt-dlp；不引入第三方下载 SDK；不绕过付费墙

## 结构（关键路径，勿读全仓库）
- 解析内核：`parser/src/main/kotlin/com/clipdown/parser/`
  - `core/ParserEngine.kt`（入口：`parse / parseText / quickDetect / bootstrap / updateConfig`）
  - `core/UrlUtil.kt`（URL 召回、归一化、去跟踪参数、平台判定、短链判定）
  - `core/PlatformRegistry.kt`（SPI 注册表）、`core/RemoteResolver.kt`（cobalt 协议兜底）
  - `parsers/*Parser.kt`（xhs/ig/x/fb/tiktok/douyin/bilibili/weibo/youtube/generic，`parsers/HtmlUtil.kt` 为通用抽取）
  - `stream/M3u8.kt`、`model/`（Platform/MediaItem/ParseResult）、`config/ParserConfig.kt`、`http/OkHttpFacade.kt`
- 下载引擎：`downloader/src/main/kotlin/com/clipdown/downloader/`
  - `DownloadController.kt`（对外唯一门面：install/enqueue/pause/resume/cancel）
  - `engine/DownloadEngine.kt`（Channel 队列 + N worker）、`engine/HttpFileDownloader.kt`（Range 续传）、
    `engine/M3u8Downloader.kt`（分片并发 + AES-128）、`engine/MediaRemuxer.kt`（MediaMuxer 合并/remux）
  - `db/TaskDatabase.kt`（手写 SQLite）、`storage/MediaStoreWriter.kt`、`notify/DownloadNotifier.kt`、`DownloadService.kt`
- App 层：`app/src/main/java/com/clipdown/app/`
  - `clip/LinkCenter.kt`（四通道汇聚 + 15s 去重）、`clip/ClipboardMonitor.kt`、`clip/ClipGateActivity.kt`（借道前台读剪贴板）、
    `clip/ClipAccessibilityService.kt`、`clip/ShareTargetActivity.kt`、`clip/BootReceiver.kt`
  - `floatwindow/FloatingWindowService.kt`（悬浮球 + 全屏弹窗宿主）、`floatwindow/ClipPopupContent.kt`（毛玻璃卡）、`floatwindow/OverlayLifecycleOwner.kt`
  - `ui/`（MainActivity、nav/AppNav、home/downloads/settings 三页）、`data/SettingsRepository.kt`（DataStore）、`data/CookieStore.kt`
  - `AndroidManifest.xml` 已含全部组件与权限声明
- 文档：`README.md`、`docs/01~05`（调研/架构/流程/界面/平台适配）

## 决策与坑
- 已做决策：三模块单向依赖；`:parser` 纯 JVM 不引 Android API；手写 SQLite 不用 Room；DataStore 配置热更新；Compose 弹窗自造 LifecycleOwner
- 已知坑：
  1. 本机 JDK 24 过高，AGP 8.x 必须用 JDK17（build.bat 已固定）
  2. `androidx.lifecycle.ViewTreeLifecycleOwner/ViewTreeSavedStateRegistryOwner` 静态引用不可靠（版本漂移），FloatingWindowService 里已改用**反射** `Class.forName(...).getMethod("set",...)` 调用，勿改回直接引用
  3. 毛玻璃模糊用 `LayoutParams.setBlurBehindRadius`（不是 `setBackgroundBlurRadius`），仅 API31+，需 `FLAG_BLUR_BEHIND` + `PixelFormat.TRANSLUCENT`
  4. Android 10+ 后台读不到剪贴板：靠 ClipGateActivity 借道 + 无障碍 + 分享通道兜底，属平台硬限制
  5. 各平台解析器依赖页面内联数据，结构易变；X 的 guest token 推导（`((id/1e15)*π).toString(36)` 去掉`.`和`0`）为 JVM 自实现进制转换
  6. B站 playurl 需 WBI 签名，已实现于 `BilibiliParser`（nav 取 key → 乱序表 mixin → md5）
  7. `settings.gradle.kts` 用 FAIL_ON_PROJECT_REPOS；`local.properties` 写死 `sdk.dir=C:\AndroidDev\sdk`（勿提交/勿删）
  8. 编译告警：`android.defaults.buildfeatures.buildconfig=true` 已废弃（gradle.properties 内，可移除）

## 命令
- 构建：`C:\AndroidDev\build.bat :app:assembleDebug --console=plain`
- 单模块编译：`C:\AndroidDev\build.bat :parser:compileKotlin` / `:downloader:compileDebugKotlin`
- 测试：`C:\AndroidDev\build.bat test`（当前无任何测试用例）
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（约 17.3 MB，当前可安装）

## 状态
- 当前状态：**首次完整实现已构建通过**（60 任务全绿，无编译错误，lint 0 报错）；仅 debug 包，未做 release/签名
- 验收标准：`assembleDebug` 成功；悬浮窗可弹、解析-下载链路真机跑通
- 下一步（按优先级）：
  1. `git init` + 首次提交（`.gitignore` 已就绪，勿提交 `local.properties`/`build/`）
  2. 真机回归：悬浮窗权限、无障碍、分享通道、抖音/小红书/X 实测
  3. 为 `:parser` 补 JVM 单测（UrlUtil 提取/归一化、M3u8Parser、PlatformRegistry 路由）
  4. release 签名 + R8 混淆验证（`app/proguard-rules.pro` 已有雏形）
  5. 可选：HLS 产物 remux 失败时保留 .ts 的用户提示；远端 cobalt 服务对接文档
