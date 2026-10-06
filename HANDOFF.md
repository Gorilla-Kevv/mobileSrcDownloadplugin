# HANDOFF

## 项目
- 路径：`F:\schoolCompWorks\clone\mobileSrcDownloadplugin`
- 技术栈：Kotlin 2.0.21 + Compose (BOM 2024.10.01)，AGP 8.7.3，minSdk 26 / target 34
- 模块：`:app`（UI/悬浮窗/剪贴板四通道）、`:parser`（纯 JVM 解析内核）、`:downloader`（下载引擎）
- git：main，remote `github.com/Gorilla-Kevv/mobileSrcDownloadplugin`（私有），全部已推送
- 环境（全在 F 盘）：JDK17 `F:\AndroidDev\jdk\jdk-17.0.20.1+1`，SDK `F:\AndroidDev\sdk`，缓存 `F:\AndroidDev\.gradle`；模拟器 AVD `clip34`（1080x2400，时钟 GMT，重启清剪贴板）

## 目标
- 已达成：剪贴板四通道/分享/无障碍 → 点气泡识别 → 自动流水线（解析→单视频/图下载，图集竖条挑选）→ 下载闭环；下载页图集分组与来源回看；IG/X/YouTube 实测（详见 PROGRESS 阶段 1-29（旧修复 8/9/10 系列已并入阶段 16/18/22-25））
- 非目标：不内嵌 yt-dlp；不绕过付费墙

## 结构（关键路径，勿读全仓库）
- 解析：`parser/`——`core/ParserEngine.kt`（quickDetect/parseSafe/配置热更新）、`core/UrlUtil.kt`（召回/归一化/平台判定）、`parsers/InstagramParser.kt`（**四通道降级：GraphQL→embed→WebView→oEmbed；ownPostScope+ownCodeSegment 双重隔离；DOM 图集兜底；sanitized 出口清洗**）、`parsers/HtmlUtil.kt`（unescapeJson 双层解码（修复 9→阶段 18））
- 下载：`downloader/`——`DownloadController.kt`（enqueue/enqueueAll 带 sourceUrl+入口 URL 清洗）、`engine/DownloadEngine.kt`（终态失败日志）、`db/TaskDatabase.kt`（v2：source_url 列）
- App：`app/clip/`（四通道+ClipGateActivity+WebViewHtmlFetcher 轮询探针+debug_last_page.html 落盘）、`app/floatwindow/`——`FloatingWindowService.kt`（**修复 12（阶段 29）：气泡+竖条同窗口一体化 BubbleWindowContent/syncBubbleWindow/touch 分区**；BubblePhase 7 相位动效；autoRecognize 流水线+保险丝）、`floatwindow/BubbleBar.kt`（**竖条内容：Mini/图集两形态，64dp 与气泡同宽**）、`ClipPopupContent.kt`（纯居中卡：Loading/单选Ready/Failed/Downloads）、`PopupUiState.kt`、`BubblePhase.kt`）、`app/ui/downloads/DownloadsScreen.kt`（图集分组/打开/来源/**时间显示**）、`app/data/SettingsRepository.kt`（autoDownload/seen_links 等）

## 决策与坑（活坑，按影响排序）
1. **IG 页面 JSON-in-JS 双重转义**（`\/`→源码 `\\/`）：三条提取路径曾各自漏网（阶段 18 unescapeJson 双层解码 + enqueue 入口清洗双保险）；**任何新提取路径必须过 unescapeJson**
2. **IG items[0] 内嵌推荐块**（2026-09 结构：items[0] 含 3 个 code）→ 阶段 22 `ownCodeSegment` 按 code 切段；**新增提取限定正帖段**
3. **图集帖 WebView 数据残缺**：`carousel_media=null`、media_type=1，子图只渲染在 DOM → 阶段 25 DOM img 兜底（排除头像 t51.2885-19/s150x150）；仅 `images.size<=1 && videos.isEmpty()` 时触发
4. **embed 通道已废**（页面无媒体数据）且**有视频时只留视频**（阶段 24，封面帧不作独立媒体）；**GraphQL 是图集完整数据唯一来源**，但代理环境下被会话-IP 风控拒（返回 HTML，无 App 层解，真机可解）
5. **URL query 截断**（修复 8→阶段 16）：URL_PATTERN 排除集勿加回半角 `?`
6. **Compose pointerInput 会杀 View 级触摸监听**：气泡拖拽/单击双击全在 View `setOnTouchListener`（坑 24）；**BubblePhase 的 Parsing/Downloading 是持续相位，新流程路径必须显式收口**（坑 25）
7. **无障碍**：manifest 勿写 `packageNames`；`adb install -r` 会清授权且可能假死（卸载重装首次启用或重启模拟器）；Android 10+ 后台剪贴板被拒，依赖借道 ClipGateActivity（onWindowFocusChanged 时机）
8. **uid 漂移**：重装后 uid 变化，adb root 写 SP 后 chown 需按新 uid
9. **测试注入坑**：应用 task 前台时 `am start SEND` 被 delivered-to-top（先 force-stop/HOME）；`adb shell` 内脚本用单引号防 `$i` 被本地展开；`MSYS_NO_PATHCONV=1` 防 Git Bash 改写设备路径
10. **Git Bash 环境**：`build.bat` 不可用（中文注释撞代码页）→ 见下方命令；PowerShell 不支持 heredoc
11. Piped 公共实例经常性波动（YouTube 解析失败文案"需要远端解析"是预期降级）；模拟器 ping 不通外网正常（ICMP）
12. **HLS 解密变换的运行时差异**：`AES/CBC/PKCS7Padding` 只有 Android/BC 注册，桌面 JVM（SunJCE）会抛 `NoSuchPaddingException`——曾被 `runCatching` 静默吞成"解密失效、直接落密文"。已改为 PKCS7→PKCS5 降级；**任何 `Cipher.getInstance` 的失败都不要静默吞，至少打日志**
13. **IG 风控保护（修复 11→阶段 28）**：InstagramParser 连续失败 ≥2 次自动冷却 10 分钟（期内 parse 零请求直接抛）；generic 兜底仅对 GENERIC 平台生效——**专属平台失败不再落 generic 抓图标垃圾**；**账号风控期测试纪律：间隔 ≥10 分钟、失败不重试（冷却自动拦）、优先非 IG 平台验证**
13. 诊断通道：日志 tag `ig-local-v1`（ctx.log：通道选择/GraphQL 失败原因/解析成功 media 清单）、`FloatingWindowService`（media 结果+污染取证）、`DownloadEngine`（终态失败+URL）；**sqlite 任务库是 403 取证位**：`sqlite3 /data/data/com.clipdown.app/databases/clipdown_tasks.db "SELECT status,url FROM tasks"`；WebView 抓取页落盘 `/sdcard/Android/data/com.clipdown.app/files/debug_last_page.html`
14. **`adb shell cat` 对二进制文件做 LF→CRLF 翻译**（Git Bash + adb.exe 路径）：备份恢复 `/data/data/.../files/datastore/*.preferences_pb` 后，应用启动立刻崩溃 `Unable to parse preferences proto / While parsing a protocol message, the input ended unexpectedly in the middle of a field`。**正确做法：用 `adb exec-out run-as <pkg> cat <path>` 或 `adb exec-out "cat <path>"`（exec-out 不走 pty 翻译）；xml 类文本 SP 受 CRLF 影响小可忽略**。本指纹为阶段 30 踩坑（release 装机冒烟：uninstall→install→恢复 cookie 与 datastore，启动即崩）

## 命令
- 构建（Git Bash）：`export JAVA_HOME="F:\\AndroidDev\\jdk\\jdk-17.0.20.1+1" GRADLE_USER_HOME="F:\\AndroidDev\\.gradle" ANDROID_HOME="F:\\AndroidDev\\sdk" ANDROID_SDK_ROOT="F:\\AndroidDev\\sdk"` 后 `/f/AndroidDev/gradle-8.9/bin/gradle.bat -p . --no-daemon -Dorg.gradle.java.home=... :app:assembleDebug :parser:test --console=plain`
- 测试：`:parser:test` **40 例**；`:downloader:testDebugUnitTest` **30 例**（HttpFileDownloader/M3u8Downloader/MediaRemuxer/TaskModels/DownloadController；自建 JDK `TestHttpServer`，无新增依赖）
- **release**：`:app:assembleRelease`（minify+shrinkResources+正式签名；**体积 18.6MB→1.77MB**）；签名由根目录 `signing.properties` 驱动，keystore `app/signing/clipdown.jks` **不入 git——务必备份（密码 clipdown2026，丢失无法升级签名）**；R8 反射 keep 见 proguard-rules.pro（ViewTree* 宿主绑定）
- adb：需非沙箱执行；装机后 `appops set com.clipdown.app SYSTEM_ALERT_WINDOW allow` + `settings put secure enabled_accessibility_services ...`；服务 `am start-foreground-service -n com.clipdown.app/.floatwindow.FloatingWindowService`
- 调试：`ACTION_DEBUG_PHASE`（--es phase parsing|parse_ok|... [--ei percent N]）直接驱动气泡状态机

## 状态
- 当前：**阶段 30（release 装机冒烟部分完成）已落档**：release APK 装机成功、R8 无运行时崩溃、X syndication 解析成功一次确认网络/解析链路；下载闭环与图集竖条受模拟器出口 IP 限制（X 404 / B 站异常格式 / IG 冷却保护）未完成（环境，非 release 回归），下载引擎由 30 例单测兜底。本 session 累计：阶段 30（**新坑 14：`adb shell cat` 二进制 CRLF 翻译损坏 DataStore proto，阶段 30 中由 release 装机恢复 datastore 触发**）
- 验收标准：`assembleRelease` 全绿 + `:parser:test`（40 例）+ `:downloader:testDebugUnitTest`（30 例）全绿 + 模拟器/真机关键链路实测 + release 冒烟
- 下一步：①真机回归（优先 X 单视频下载闭环 + X 图集竖条；家宽 IP 通常绕过 X 风控）②IG 风控恢复后复测阶段 25 ③阶段 29 四项气泡交互（X 图集竖条 / 拖到下半屏向上生长 / 再点收起 / IG 冷却文案）
- 文档：PROGRESS.md 全阶段记录（阶段 1-30，头部进度看板，含旧编号对照）；PLAN.md（阶段 14-18 计划，已全部实现）
