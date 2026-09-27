# HANDOFF

## 项目
- 路径：`F:\schoolCompWorks\clone\mobileSrcDownloadplugin`
- 技术栈：Kotlin 2.0.21 + Jetpack Compose (BOM 2024.10.01)，AGP 8.7.3，Gradle 8.9，minSdk 26 / target & compile 34
- 模块：`:app`（UI/悬浮窗/剪贴板）、`:parser`（纯 JVM 解析内核）、`:downloader`（Android Library 下载引擎）
- 版本控制：git 已初始化（`main` 分支），远程 `origin = https://github.com/Gorilla-Kevv/mobileSrcDownloadplugin.git`（私有，gh CLI 账号 Gorilla-Kevv），已推送并跟踪；工作树与 origin/main 同步
- 开发环境（**已迁移至 F 盘**，旧 C:\AndroidDev 已删除）：JDK17 `F:\AndroidDev\jdk\jdk-17.0.20.1+1`，SDK `F:\AndroidDev\sdk`，Gradle `F:\AndroidDev\gradle-8.9`，依赖缓存 `F:\AndroidDev\.gradle`；用户级环境变量（JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / GRADLE_USER_HOME / PATH）均已指向 F 盘；统一构建脚本 `F:\AndroidDev\build.bat`（内含全套环境变量，可带任意 gradle 参数）

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
  1. 本机 JDK 24 过高，AGP 8.x 必须用 JDK17（build.bat 已固定为 F:\AndroidDev 下的 JDK17）
  2. 环境已整体迁移到 `F:\AndroidDev`（2026-09-27 验证通过）；若再迁移，需同步改：用户级环境变量、`local.properties` 的 `sdk.dir`、`F:\AndroidDev\build.bat` 内的路径
  3. `androidx.lifecycle.ViewTreeLifecycleOwner/ViewTreeSavedStateRegistryOwner` 静态引用不可靠（版本漂移），FloatingWindowService 里已改用**反射** `Class.forName(...).getMethod("set",...)` 调用，勿改回直接引用
  4. 毛玻璃模糊用 `LayoutParams.setBlurBehindRadius`（不是 `setBackgroundBlurRadius`），仅 API31+，需 `FLAG_BLUR_BEHIND` + `PixelFormat.TRANSLUCENT`
  5. Android 10+ 后台读不到剪贴板：靠 ClipGateActivity 借道 + 无障碍 + 分享通道兜底，属平台硬限制
  6. 各平台解析器依赖页面内联数据，结构易变；X 的 guest token 推导（`((id/1e15)*π).toString(36)` 去掉`.`和`0`）为 JVM 自实现进制转换
  7. B站 playurl 需 WBI 签名，已实现于 `BilibiliParser`（nav 取 key → 乱序表 mixin → md5）
  8. `settings.gradle.kts` 用 FAIL_ON_PROJECT_REPOS；`local.properties` 写死 `sdk.dir=F:\AndroidDev\sdk`（勿提交/勿删）
  9. 编译告警：`android.defaults.buildfeatures.buildconfig=true` 已废弃（gradle.properties 内，可移除）
  10. 新版 cmdline-tools 的 android CLI（重写版）在 Windows 有两个 bug：大文件解压崩溃（0xC0000409）与 `;` 分隔包名被拆分；装组件用 classic cmdline-tools 12.0（临时解压在 `%TEMP%\cmdtools-classic`，可移至 `F:\AndroidDev\cmdline-tools-classic`）
  11. AVD `clip34` 为手写配置（avdmanager 的 SDK 根解析有问题）：`C:\Users\kevin\.android\avd\clip34.avd\config.ini` + 同级 `clip34.ini`；启动 `F:\AndroidDev\sdk\emulator\emulator.exe -avd clip34 -gpu host -no-snapshot -http-proxy http://10.0.2.2:7890`（10.0.2.2=宿主机回环，7890=宿主 Clash；另可 `adb shell settings put global http_proxy 10.0.2.2:7890` 设应用层全局代理，YouTube/Google 已实测可达）；adb 在 `F:\AndroidDev\sdk\platform-tools\adb.exe`
  12. B 站风控（2026-09-27 实测）：`api.bilibili.com` 的 view 可匿名访问，但 nav（无 wbi_img）与 playurl（返回 404/412 HTML 错误页）对无登录态+可疑 IP/指纹持续拦截；`BilibiliParser` 已做 cookie 预热（访问视频页收 buvid3）与诊断日志，彻底解法 = 设置页注入 SESSDATA 或远端解析服务；GenericParser 会把外链播放器 HTML 存成 .mp4，待加 magic bytes 校验
  13. YouTube/Piped（2026-09-27 实测）：Piped 的 `format` 实际取值是 `HLS`/`MP4`/`MPEG_4`（不是 `MIME_TYPE_VIDEO_HLS`），`YoutubeParser` 已按此识别并把 HLS 标记 `isPlaylist=true`；`DownloadController.enqueue` 另有 `.m3u8` 后缀兜底路由。LBRY 镜像（odycdn）存在内容错位（返回无关长视频的播放列表），不可信，优先选 360p itag-18（googlevideo 代理直链）已实测产出真实 MP4；改进方向=用 Piped `duration` 做时长 sanity check

## 命令
- 构建：`F:\AndroidDev\build.bat :app:assembleDebug --console=plain`
- 单模块编译：`F:\AndroidDev\build.bat :parser:compileKotlin` / `F:\AndroidDev\build.bat :downloader:compileDebugKotlin`
- 测试：`F:\AndroidDev\build.bat test`（当前无任何测试用例）
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（约 17.3 MB，当前可安装）
- 模拟器：启动见坑 11；装 APK `adb install -r`；预授权悬浮窗 `adb shell appops set com.clipdown.app SYSTEM_ALERT_WINDOW allow`，无障碍 `adb shell settings put secure enabled_accessibility_services com.clipdown.app/com.clipdown.app.clip.ClipAccessibilityService`

## 状态
- 当前状态：**首次完整实现已构建通过**（60 任务全绿，无编译错误，lint 0 报错）；仅 debug 包，未做 release/签名
- 2026-09-27：开发环境整体迁移至 `F:\AndroidDev` 并验证 `assembleDebug` 通过（旧 C:\AndroidDev 已删除；`local.properties`、`build.bat`、文档已同步更新）
- 验收标准：`assembleDebug` 成功；悬浮窗可弹、解析-下载链路真机跑通
- 下一步（按优先级）：
  1. ~~git init + 首次提交~~（已完成，见 PROGRESS 阶段 2；远程 origin 已推送）
  2. ~~AVD 模拟器冒烟测试~~（已完成，见 PROGRESS 阶段 3；B 站解析因风控需真机/登录态复测）
  3. 真机回归：悬浮窗权限、无障碍、分享通道、抖音/小红书/X 实测（B 站专属解析路径在真机+家宽 IP 下复验）
  4. 为 `:parser` 补 JVM 单测（UrlUtil 提取/归一化、M3u8Parser、PlatformRegistry 路由）
  5. release 签名 + R8 混淆验证（`app/proguard-rules.pro` 已有雏形）
  6. 可选：HLS 产物 remux 失败时保留 .ts 的用户提示；远端 cobalt 服务对接文档；GenericParser 产物 magic bytes 校验
