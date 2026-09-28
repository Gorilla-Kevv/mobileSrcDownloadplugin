# HANDOFF

## 项目
- 路径：`F:\schoolCompWorks\clone\mobileSrcDownloadplugin`
- 技术栈：Kotlin 2.0.21 + Jetpack Compose (BOM 2024.10.01)，AGP 8.7.3，Gradle 8.9，minSdk 26 / target & compile 34
- 模块：`:app`（UI/悬浮窗/剪贴板四通道）、`:parser`（纯 JVM 解析内核）、`:downloader`（Android Library 下载引擎）
- 版本控制：`main` 分支，远程 `origin = https://github.com/Gorilla-Kevv/mobileSrcDownloadplugin.git`（私有，gh 账号 Gorilla-Kevv），已推送跟踪
- 开发环境（**全部在 F 盘**）：JDK17 `F:\AndroidDev\jdk\jdk-17.0.20.1+1`，SDK `F:\AndroidDev\sdk`，Gradle `F:\AndroidDev\gradle-8.9`，缓存 `F:\AndroidDev\.gradle`；统一构建脚本 `F:\AndroidDev\build.bat`（内含全套环境变量）

## 目标
- 已达成：剪贴板/分享/无障碍识别 → 解析 → 悬浮窗内下载闭环；IG/X/YouTube/小红书/B站 实测（详见 PROGRESS 阶段 1-13）
- 非目标：不内嵌 Python/yt-dlp；不引第三方下载 SDK；不绕过付费墙

## 结构（关键路径，勿读全仓库）
- 解析内核：`parser/src/main/kotlin/com/clipdown/parser/`
  - `core/ParserEngine.kt`（`parse / parseText / quickDetect`，quickDetect 供悬浮窗快判）
  - `core/UrlUtil.kt`（召回/归一化（剥 mediaViewer 变体与弯引号）/去跟踪参数/平台判定/短链判定）
  - `parsers/InstagramParser.kt`（**ownPostScope 正帖隔离** + 新版 xdt_api 结构提取，见坑 15/16）、`parsers/HtmlUtil.kt`
  - 其余 `parsers/*Parser.kt`、`core/PlatformRegistry.kt`、`core/RemoteResolver.kt`（cobalt 兜底）、`stream/M3u8.kt`
- 下载引擎：`downloader/`：`DownloadController.kt`（门面）、`engine/DownloadEngine.kt`（**终态事件已补发**）、`HttpFileDownloader`（Range 续传）、`M3u8Downloader`（AES-128）、`MediaRemuxer`、`db/TaskDatabase`（手写 SQLite）、`storage/MediaStoreWriter`
- App 层：`app/src/main/java/com/clipdown/app/`
  - `clip/ClipAccessibilityService.kt`（**四通道**：复制特征借道/剪贴板直读/窗口逐节点扫描/复制提示文本；尾部合并防抖）
  - `clip/ClipGateActivity.kt`（借道前台读剪贴板，**taskAffinity="" 独占任务栈**，onWindowFocusChanged 时机）
  - `clip/LinkCenter.kt`（通道汇聚+15s 去重）、`ClipboardMonitor`、`ShareTargetActivity`、`BootReceiver`
  - `floatwindow/FloatingWindowService.kt`（气泡=纯入口→迷你面板→解析弹窗；downloadActive 守卫；suppressedUrl 抑制）、`ClipPopupContent.kt`（毛玻璃卡/迷你面板）、`PopupUiState.kt`（Mini/Loading/Ready/Failed）
  - `clip/WebViewHtmlFetcher.kt`（Cookie 注入渲染抓取+轮询探针+**debug_last_page.html 落盘**）
  - `data/CookieStore.kt`（SP `clipdown_cookies`，key=platform.id）、`data/SettingsRepository.kt`（DataStore，含 `seen_links` 识别记忆）
- 文档：`README.md`、`docs/01~05`、`PROGRESS.md`（阶段 1-13 全记录）

## 决策与坑
- 已做决策：三模块单向依赖；`:parser` 纯 JVM；手写 SQLite；DataStore 热更新；Compose 弹窗反射挂 LifecycleOwner（坑 3 勿改回）
- 已知坑（1-13 见 PROGRESS 阶段记录，14+ 为悬浮窗/IG 专项）：
  14. **无障碍 `packageNames=""`（空串）= 空数组 = 零事件**：不写该属性才收全部包。模拟器 `adb install -r` 或反复 `settings put` 切换后会出现「Bound 但事件永不派发」假死——卸载重装后**首次启用**可靠；彻底恢复需重启模拟器
  15. **IG 数据结构已迁移**：`video_url/playable_url/display_url` 全消失 → `video_versions:[{width,height,url}]` / `image_versions2.candidates`（URL 含 `\/` 与 `\u0025` 双重转义）；解析范围必须限定 `xdt_api__v1__media__shortcode__web_info.items[0]`（正帖容器）——页面内嵌「更多帖子」推荐流（image_versions2 达 35 个），全页扫描会把陌生帖视频混进结果
  16. **/p/ 与 /reel/ 服务端渲染行为不同**：WebView fetch 硬编码 /reel/ 会让 /p/ 链接白等 40s 超时；已改原路径优先。`/p/`+登录态 5.5s 即出媒体页（1556KB）
  17. **Android 10+ 剪贴板后台读取被拒**（无障碍服务也无豁免，ClipboardService Deny）：复制特征（Toast"已复制"/窗口提示文本）→ 借道 ClipGateActivity；gate 必须在 `onWindowFocusChanged` 读（onResume 早于焦点授予必被拒）
  18. **IG 风控**（2026-09-28）：高频登录态解析 + 模拟器 + 数据中心 IP 触发 "Your email address may not be secure" 强制页，App feed 刷新失败即此因（非网络问题；宿主 Clash→IG 200 验证过）。解法=完成验证/换号/降频/住宅 IP
  19. **Cookie adb root 注入法**：写 `/data/data/com.clipdown.app/shared_prefs/clipdown_cookies.xml`（key=`instagram`）+ chown **当前 uid**（重装后 uid 会变 10204→10205，chown 旧 uid = 读不到，注入 0 字符）→ force-stop 重启生效。验证信号：WebView title 从 "unavailable" 变 "Instagram"
  20. **mediaViewer 变体绕过去重**：视频播放时 X 地址栏变 `/mediaViewer`，字符串不同即重弹——normalize 已剥离；主页/登录页 URL（无 pathHints）也已过滤不弹窗
  21. PowerShell **不支持 heredoc**（`cat <<EOF`）→ git 多行提交用 `git commit -F 文件`；git 偶发不在 PATH，用完整路径 `C:\Program Files\Git\cmd\git.exe`
  22. 模拟器时钟时区为 GMT（显示差 8h，绝对时间同步，TLS 不受影响）；模拟器 ping 不通外网属正常（ICMP 不走 http_proxy）
  23. 下载引擎终态（MERGING/COMPLETED/FAILED）曾从不发进度事件（文件落盘但 UI 卡 100%）——已在 DownloadEngine execute/runTask 补发，勿删

## 命令
- 构建：`F:\AndroidDev\build.bat :app:assembleDebug --console=plain`
- 测试：`F:\AndroidDev\build.bat :parser:test --console=plain`（**7 套件 35 例**，全绿）
- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 模拟器：见坑 11（启动带 `-http-proxy http://10.0.2.2:7890`）；装 APK `adb install -r`；预授权 `adb shell appops set com.clipdown.app SYSTEM_ALERT_WINDOW allow`；无障碍 `adb shell settings put secure enabled_accessibility_services com.clipdown.app/com.clipdown.app.clip.ClipAccessibilityService` + `settings put secure accessibility_enabled 1`

## 状态
- 当前状态：**交互重构 + IG 解析打通已完成**（阶段 11-13，提交 580b0d3/7e543f2/fa76c43/b47f426/0fdbd03）；气泡=纯入口（用户头像 logo）→迷你面板→识别；识别记忆持久化；弹窗内下载闭环；IG reel/图集/视频帖本地解析全通
- 验收标准：`assembleDebug` + `:parser:test` 全绿；IG/X/YouTube 真链路实测出媒体并可下载
- 下一步（按优先级）：
  1. **IG 风控解除**（坑 18）：完成邮箱验证或换号后复测刷新；测试节奏放缓
  2. 真机回归（家宽 IP）：B站专属解析、抖音/小红书、悬浮窗全链路
  3. release 签名 + R8（proguard 雏形已有）
  4. 小项：GenericParser 产物 magic bytes 校验；IG WebView 渲染页 debug 落盘开关化；:downloader 单测
