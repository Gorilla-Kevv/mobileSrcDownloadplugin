# HANDOFF

## 项目
- 路径：`F:\schoolCompWorks\clone\mobileSrcDownloadplugin`
- 技术栈：Kotlin 2.0.21 + Compose (BOM 2024.10.01)，AGP 8.7.3，minSdk 26 / target 34
- 模块：`:app`（UI/悬浮窗/剪贴板四通道）、`:parser`（纯 JVM 解析内核）、`:downloader`（下载引擎）
- git：main，remote `github.com/Gorilla-Kevv/mobileSrcDownloadplugin`（私有），全部已推送
- 环境（全在 F 盘）：JDK17 `F:\AndroidDev\jdk\jdk-17.0.20.1+1`，SDK `F:\AndroidDev\sdk`，缓存 `F:\AndroidDev\.gradle`；**AVD `clip34`（1080x2400，时钟 GMT，重启清剪贴板）住 `F:\AndroidDev\avd`（2026-10-12 从 C:\Users\kevin\.android\avd 迁入，靠用户级环境变量 `ANDROID_AVD_HOME` 定位；C 盘 .android 只剩 89MB 密钥/小文件属正常）**

## 目标
- 已达成：剪贴板四通道/分享/无障碍 → 点气泡识别 → 自动流水线（解析→单视频/图下载，图集竖条挑选）→ 下载闭环；下载页图集分组与来源回看；IG/X/YouTube 实测（详见 PROGRESS 阶段 1-29（旧修复 8/9/10 系列已并入阶段 16/18/22-25））
- 非目标：不内嵌 yt-dlp；不绕过付费墙

## 结构（关键路径，勿读全仓库）
- 解析：`parser/`——`core/ParserEngine.kt`（quickDetect/parseSafe/配置热更新/**webFetcherScroll 接线**）、`core/UrlUtil.kt`（召回/归一化/平台判定）、`parsers/InstagramParser.kt`（**四通道降级：GraphQL→embed→WebView→oEmbed（后三通道 2026-10 起对匿名已死，见坑 31）；GraphQL 提取 edge_sidecar_to_children/carousel_media/递归兜底（阶段 47）；ownPostScope+ownCodeSegment 双重隔离；DOM 图集兜底；sanitized 出口清洗**）、`parsers/HtmlUtil.kt`（unescapeJson 双层解码（修复 9→阶段 18）+ **jsonField 两段式**）、`parsers/XiaohongshuProfileParser.kt`（**SSR 首屏 + WebView 回收的 user_posted 接口分页；arraySliceAfter 需匹配完整 "key":**）、`parsers/XProfileParser.kt`（**X syndication 时间线，免登录**）、`parsers/InstagramProfileParser.kt`（**web_profile_info→og/链接扫描→WebView**）、`parsers/IgRiskGuard.kt`（**IG 冷却保险丝，单篇与主页共享**）
- 下载：`downloader/`——`DownloadController.kt`（enqueue/enqueueAll 带 sourceUrl+入口 URL 清洗）、`engine/DownloadEngine.kt`（终态失败日志）、`db/TaskDatabase.kt`（v2：source_url 列）
- App：`app/clip/`（四通道+ClipGateActivity+WebViewHtmlFetcher 轮询探针+**XHR 分页钩子/桌面 UA 开关**+debug_last_page.html 落盘）、`app/floatwindow/`——`FloatingWindowService.kt`（**修复 12（阶段 29）：气泡+竖条同窗口一体化 BubbleWindowContent/syncBubbleWindow/touch 分区**；BubblePhase 7 相位动效；autoRecognize 流水线+保险丝）、`floatwindow/BubbleBar.kt`（**竖条内容：Mini/图集/多功能面板三形态，64dp 与气泡同宽**）、`ClipPopupContent.kt`（纯居中卡：Loading/单选Ready/Failed）、`PopupUiState.kt`（含 **Panel 多功能面板态，阶段 46**）、`BubblePhase.kt`）、`app/ui/downloads/DownloadsScreen.kt`（图集分组/打开/来源/**时间显示**）、`app/ui/profile/`——`ProfileCenter.kt`（**多标签 sessions/activeUrl + 加载更多合并去重 + filesDir JSON 持久化**）、`ProfileScreen.kt`（标签栏/信息卡/筛选/两列网格/吸底下载/详情面板/翻页区）、`app/data/SettingsRepository.kt`（autoDownload/seen_links 等）、`app/floatwindow/`——`FloatingWindowService.kt`（**修复 12（阶段 29）：气泡+竖条同窗口一体化 BubbleWindowContent/syncBubbleWindow/touch 分区**；BubblePhase 7 相位动效；autoRecognize 流水线+保险丝）、`floatwindow/BubbleBar.kt`（**竖条内容：Mini/图集/多功能面板三形态，64dp 与气泡同宽**）、`ClipPopupContent.kt`（纯居中卡：Loading/单选Ready/Failed）、`PopupUiState.kt`（含 **Panel 多功能面板态，阶段 46**）、`BubblePhase.kt`）、`app/ui/downloads/DownloadsScreen.kt`（图集分组/打开/来源/**时间显示**）、`app/data/SettingsRepository.kt`（autoDownload/seen_links 等）

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
13. **IG 风控保护（修复 11→阶段 28，阶段 41 升级为共享保险丝）**：冷却逻辑已抽到 `parsers/IgRiskGuard.kt`——**单篇 `InstagramParser` 与主页 `InstagramProfileParser` 共用一份失败计数**（连续失败 ≥2 次冷却 10 分钟，期内两条链路都零请求直接抛），避免"主页失败→改点单篇"绕开冷却轰炸账号；**缺 Cookie 不计入失败**（否则用户没配好 Cookie 就自锁 10 分钟）。generic 兜底仅对 GENERIC 平台生效——**专属平台失败不再落 generic 抓图标垃圾**；**账号风控期测试纪律：间隔 ≥10 分钟、失败不重试（冷却自动拦）、优先非 IG 平台验证**
13. 诊断通道：日志 tag `ig-local-v1`（ctx.log：通道选择/GraphQL 失败原因/解析成功 media 清单）、`FloatingWindowService`（media 结果+污染取证）、`DownloadEngine`（终态失败+URL）；**sqlite 任务库是 403 取证位**：`sqlite3 /data/data/com.clipdown.app/databases/clipdown_tasks.db "SELECT status,url FROM tasks"`；WebView 抓取页落盘 `/sdcard/Android/data/com.clipdown.app/files/debug_last_page.html`
14. **`adb shell cat` 对二进制文件做 LF→CRLF 翻译**（Git Bash + adb.exe 路径）：备份恢复 `/data/data/.../files/datastore/*.preferences_pb` 后，应用启动立刻崩溃 `Unable to parse preferences proto / While parsing a protocol message, the input ended unexpectedly in the middle of a field`。**正确做法：用 `adb exec-out run-as <pkg> cat <path>` 或 `adb exec-out "cat <path>"`（exec-out 不走 pty 翻译）；xml 类文本 SP 受 CRLF 影响小可忽略**。本指纹为阶段 30 踩坑（release 装机冒烟：uninstall→install→恢复 cookie 与 datastore，启动即崩）
15. **root push 文件进 `/data/data/<pkg>` 会留下权限污染**：只 chown 文件不够，DataStore 往目录写 `.tmp` 时会 `EACCES` → **主线程 FATAL** → 进程被杀 → 正在进行的 WebView 抓取永久挂起（日志停在 `fetch 开始`，无后续）。**恢复数据后必须 `chown -R <uid>:<uid> /data/data/<pkg>`（或干脆 `pm clear` 后只恢复 xml 类 cookie），并确认 `ls -lZ` 的 SELinux 上下文是 `u:object_r:app_data_file:s0:c<uid>,...`**。阶段 31 由小红书链路实测暴露
16. **小红书结构要点（阶段 31/32 实测，设备落盘 `debug_last_page.html` 取证）**：
    - **短链域名是 `xhslink.cn`**（不是 .com），App 分享文案里实际下发的就是它；漏登记会被判"暂不支持该链接"
    - 短链返回 **200 + 纯 `<a href="https://www.xiaohongshu.com/discovery/item/<id>?xsec_token=...">`**，既不是 3xx 也不是 meta refresh/JS 跳转（`ParserEngine.redirectFromHtml` 已覆盖三种形态）
    - **桌面 UA → 302 /login（登录墙）；移动端 UA 才返回带 SSR 的笔记页**（2026-10 对照实测）。解析器直连已固定用 `headersFor(platform, desktop=false)`
    - 移动端 SSR 结构与桌面端不同：**没有 `noteDetailMap`**，图片在 `imageList[].url`（带 `fileId`，后缀 `!h5_1080jpg`），标题是 `title`，作者是 `user.nickName`（`atUserList[].nickName` 是 @提及的人，全页取首个会取错）
    - 图集同一张图同时下发 `urlPre`（预览）与 `urlDefault`（默认画质），**文件 ID 相同**（`.../notes_pre_post/<fileId>!nd_xxx`）→ 必须按文件 ID 去重
    - 视频笔记同时有 `masterUrl` 与 `imageList`（封面帧）→ **有视频时必须丢弃图片**（与 IG 修复 10c 同源）
    - **失效笔记不返回 4xx**：渲染 title=「小红书 - 你访问的页面不见了」；**登录墙/探索页 title 都是「小红书 - 你的生活兴趣社区」**（三者都塞推荐流，必须按 title 判定失败）
    - 笔记详情页的 SSR 标志是 `noteDetailMap`（桌面）/ `imageList`（移动）；探索页两者都无
    - **视频帖**（`type=video`，阶段 33 取证）：视频在 `video.media.stream.h264[].masterUrl` + `backupUrls`，**位置在 imageList 窗口之外**（必须全页扫描 `extractVideos`）；封面在 `imageList[0].infoList[]`，`imageScene` 为 `H5_DTL`/`H5_PRV` 两形态但 **fileId 相同**（去重后仅 1 张）。视频 URL 实测 `Content-Type: video/mp4`、`Accept-Ranges: bytes`（206 续传可用）、**必须带 `Referer: https://www.xiaohongshu.com/`**（解析器已挂在 MediaItem.headers 上）
17. **小红书反爬含客户端指纹维度（阶段 32 结论；阶段 42 真机修正）**：同一 URL、同一移动端 UA、同一请求头，**主机 curl 得 200（含 imageList），App 内 OkHttp 与 WebView 都被 302 到 `/login`**（日志 `直连结果：code=200 finalUrl=.../login?redirectPath=... imageList=false`）。已排除：xsec_token 过期（1.5h 后主机仍 200）、`apptime`/`share_id` 被 normalize 剥离、UA 与 client hints 不一致、WebView 残留 cookie、请求头组合。**阶段 42 真机实测：小红书笔记免登录解析成功**——302 登录墙是模拟器环境（指纹/IP）问题，`Platform.XIAOHONGSHU.loginRequired` 已改 false（平台列表"公开可解析"）；**注意 `CookieStore.platformsNeedingCookie()` 已与 loginRequired 解耦为显式清单，主页解析仍需 Cookie，设置页入口别丢**
18. **模拟器代理残留会静默破坏应用网络（阶段 34/35 踩坑）**：`settings put global http_proxy 10.0.2.2:14047` 会被 Android 拆成 `global_http_proxy_host` / `global_http_proxy_port` 两个键，**只 delete `http_proxy` 无效**；且已生效的代理会缓存在网络配置里，必须**两个键都删 + 重启**才复位。症状是应用内所有 OkHttp 请求都报 `SocketTimeoutException: failed to connect to /10.0.2.2 (port 14047)`。另外**模拟器 DNS 解析不了 github.com**（`UnknownHostException: Unable to resolve host "github.com"`）——本机所在网络直连 GitHub 亦超时（需代理），故**应用内更新在模拟器上必然失败，只能真机验证**
19. **Compose 里做网络请求必须切 IO 线程**：`LaunchedEffect` 跑在主线程，直接调阻塞式 OkHttp 会抛 `NetworkOnMainThreadException`（阶段 35 在设置页「检查更新」实测到，表现为"检查失败：NetworkOnMainThreadException"）。已改为 `withContext(Dispatchers.IO)`
20. **小红书主页：SSR 需要「桌面 UA + Cookie」，且短链展开要防"登录页陷阱"**（阶段 37 实测取证）：
    - 主页 `user/profile/<userId>` 三种组合实测：**桌面 UA + Cookie → 200/292KB，`userPageData` + `noteCard`×32 完整 SSR**；移动 UA + Cookie → 53KB 客户端渲染空壳；无 Cookie → 302 `/login`。原因：浏览器导出的 Cookie 是 **PC 网页版会话**（`xsecappid=xhs-pc-web`），与移动 UA 客户端不一致。故 `XiaohongshuProfileParser` 走 **桌面 UA → 移动 UA → WebView** 三级降级
    - **每篇笔记 URL 必须带 SSR 里的 `xsecToken`**（裸 `/explore/<id>` 被判无效链接）
    - **短链展开的登录页陷阱**：`expand()` 跟随重定向会落到 `/login?redirectPath=<真实地址>`，若直接返回 final 就会把登录页当目标（实测导致主页链接被判成笔记页、整条链路进不去）→ 已用 `loginRedirectTarget()` 从 `redirectPath` 还原
21. **`HtmlUtil.jsonField` 的两个边界（阶段 37 修复，影响所有平台）**：①含**转义引号**的值曾被截断（标题「…坦承\"毕生最大遗憾\"…」只取前半段）；②**无引号数字**（`"posted":4127`）因可选结尾引号吃掉下一个键的引号而取不到。现为"先带引号（惰性 + 后瞻分隔符）、再退无引号"两段式；`unescapeJson` 也补齐了 `\"` `\n` `\t` 等常规转义
22. **Compose 多标签会话的持久化要走 `@Serializable` 扩展函数（阶段 38）**：`encodeToString`/`decodeFromString` 是**扩展函数**，必须显式 `import kotlinx.serialization.encodeToString` 等，否则编译器匹配到 `Json` 类的另一重载 (String, Strategy → Value)，报"Cannot infer type for this parameter"。`ProfileResult` 已有 `@Serializable` 且依赖的 `kotlinx-serialization-json` 通过 `:parser` 模块传递到 `:app`，在 `:app` 也需要 `kotlin("plugin.serialization")` 插件（app/build.gradle.kts 已配）
23. **模拟器代理会"劫持"应用的所有网络**（阶段 35 坑 18 续）：模拟器从主机环境继承 `http_proxy=http://...`，被拆成 `global_http_proxy_host/_port` 两个 settings 键；删除 `http_proxy` 不够，要两键都删 + 重启**。最稳的预防：用 `env -u http_proxy -u https_proxy ...` 启动模拟器，根本不让它继承
24. **debug 包绝不能继承 release 正式签名（阶段 39 阻塞级）**：`signing.properties` 存在时若把签名只写在 `buildTypes.release`，debug 产物会变成"正式签名 + 不可调试"——`adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`/`VERSION_DOWNGRADE`、`run-as` 报 `package not debuggable`，**更阴的是设备上一直跑旧包，会把"UI 没渲染""功能无效"等假象全引到排查里**。已在 `buildTypes.debug` 显式设回 debug 签名；换签名需 `uninstall` 后重装，Cookie 用 `adb push /data/local/tmp` + `run-as cp` 恢复（避开坑 14/15）
25. **装机实测前必须先确认设备上就是刚构建的包**：`aapt2 dump badging <apk>` 看 versionCode/debuggable，与 `dumpsys package` 对比；不一致时别急着怀疑业务代码（阶段 39 因此在"翻页区没渲染"上空转了十几轮）
26. **WebView 抓取必须给它真实视口，且别改页面的滚动模型（阶段 39-40 定论）**：未附加到窗口的 WebView 尺寸为 0 → 页面里 `window.innerHeight=0`、`documentElement.clientHeight=0`，**基于视口的 IntersectionObserver 永不触发**，主页虚拟列表只会给出初始预取的那几页，怎么滚都不翻页。修法：创建后手动 `measure(EXACTLY 屏幕宽高)+layout(0,0,w,h)`（无需窗口、无需额外权限）。**反例**：阶段 39 曾用"给容器强制 `maxHeight`+`overflowY:scroll` 制造溢出"来让 `scrollTop` 生效——拿到真实视口后这招会变成**负作用**（滚动模型从整页 window 滚变成容器内滚，而页面监听挂在 window 上，实测 pages 从 3 掉到 1）。正确姿势：容器保持自然高度、**滚 window**，并按「顶→1/3→2/3→底」阶梯扫动制造哨兵离开/进入
27. **WebView 回收页面内 XHR 响应的四条铁律（阶段 39-40）**：①注入 JS 里**任何语法错都只表现为回调收到 `null`**（`evaluateJavascript` 不报错），改完必跑 `node scripts/check-inject-js.js`；②`onPageStarted` 里的 `evaluateJavascript` 排在页面脚本之后，**首屏请求抓不到**，但滚动触发的分页请求抓得到（滚动前再幂等补注入一次）；③回传**不能把响应写进 DOM 再取 `outerHTML`**——HTML 序列化会把引号变成 `&quot;` 使 JSON 报废，必须走 `JSON.stringify(JSON.stringify(...))` 两层编码；④`evaluateJavascript` 的返回值只保证是 **JSON 字面量**：数字/布尔不带引号，`nextValue() as? String` 恒为 null（阶段 39 的收口轮询因此一直读到 0、提前收口），要么 JS 侧 `String(...)` 包一层，要么别按 String 强转

28. **同一份平台 Cookie 绝不在多客户端交叉复用（阶段 41 实测代价）**：XHS 的 `web_session` 在"模拟器 App 内 OkHttp/WebView"与"主机 curl 同一份 Cookie"之间来回使用后，**服务端把会话打成失效**——同一请求从 291KB/32 篇 SSR 退化为 40KB 登录墙（notes=0），主机与设备同时失败。症状与"代码回归"极像，务必先用**同一条 curl 复测两遍**（间隔一段时间）判断是会话失效还是代码问题；调试期固定一个客户端用 Cookie
29. **X 主页免登录可用、IG 主页必须登录（阶段 41 定性，修正旧结论）**：X 的公开嵌入时间线 `https://syndication.twitter.com/srv/timeline-profile/screen-name/<handle>` 返回 Next.js 页面，推文在 `__NEXT_DATA__` 的 `props.pageProps.timeline.entries[].content.tweet`（含完整 `user` 与 `entities.media[]`），**无需 Cookie、无需签名**，但只有最近约 100 条、无游标 → `hasMore` 必须为 false。IG 相反：`web_profile_info` 免登录直接 429，主页 HTML 免登录返回 637KB 登录墙（**无 `og:*`、无帖子链接**），所以 IG 主页没有免登录路径
30. **`adb shell input text` 在 Compose 输入框上会随机丢字（阶段 41 测试环境坑）**：长 URL 反复被截成 `user/p`、`xsec_token=YB` 等形态，表现为"解析失败"的假回归。可靠做法：**逐字符 `input text`**（一次一个字符），并在判定前先核对 `ParserEngine: linkKind … url=` 日志里的 URL 是否完整

31. **IG 匿名通道全线失效与阶段 52 全面修复**：游客态 `/p/{code}/embed/captioned/` 与帖子页已是 JS 空壳（633KB/207KB 无媒体键），oEmbed 停服。阶段 52 全面增强：
    - **移动端 REST info 接口原生支持**：`shortcodeToMediaId` 数学逆运算 shortcode 为 numeric pk，登录态优先直连 `https://i.instagram.com/api/v1/media/{media_id}/info/`，原生获取 `items[0]`，彻底摆脱 LSD 与 doc_id 变动风险；
    - **GraphQL 2026 演进**：兼容 `xig_polaris_media`（包裹在 `if_not_gated_logged_out` 内）与 `xdt_shortcode_media`；自动从落地页 HTML / Set-Cookie 捕获缺失的 `csrftoken` 补齐；补全 `X-Requested-With`、`X-FB-Friendly-Name` 防止被 302 弹回登录页；
    - **WebView DOM 视频与多路径兜底**：直接抓取 DOM 渲染出的 `<video src="...">` 与 `<source src="...">` 直链；`/reel/` 与 `/p/` 双路径渲染探测；
    - **风控误锁根治**：`CookieStore.put` / `remove` 联动 `IgRiskGuard.reset()`，更新 Cookie 后立即重置冷却；区分认证失效与平台限流。

## 命令
- 构建（Git Bash）：`export JAVA_HOME="F:\\AndroidDev\\jdk\\jdk-17.0.20.1+1" GRADLE_USER_HOME="F:\\AndroidDev\\.gradle" ANDROID_HOME="F:\\AndroidDev\\sdk" ANDROID_SDK_ROOT="F:\\AndroidDev\\sdk"` 后 `/f/AndroidDev/gradle-8.9/bin/gradle.bat -p . --no-daemon -Dorg.gradle.java.home=... :app:assembleDebug :parser:test --console=plain`
- 测试：`:parser:test` **99 例**；`:downloader:testDebugUnitTest` **30 例**（HttpFileDownloader/M3u8Downloader/MediaRemuxer/TaskModels/DownloadController；自建 JDK `TestHttpServer`，无新增依赖）；`:app:testDebugUnitTest` 4 例（更新清单契约）
- **模拟器要访问被墙站点（X/IG）**：本机代理常只监听 `127.0.0.1:7890`，模拟器够不到 → 起一个 TCP 转发到 `0.0.0.0:<port>`（node 十几行），再 `adb shell settings put global http_proxy 10.0.2.2:<port>`；**测完删 `http_proxy` + `global_http_proxy_host` + `global_http_proxy_port` 三个键并重启模拟器**（坑 18/23），并关掉转发进程
- **注入 JS 语法自检**：`node scripts/check-inject-js.js`（从 `WebViewHtmlFetcher` 的 Kotlin 拼串里抽出 XHR 钩子并校验括号自平衡——改过钩子必须跑，语法错在设备上只表现为"钩子静默不生效"）
- **release**：`:app:assembleRelease`（minify+shrinkResources+正式签名；**体积 18.6MB→1.77MB**）；签名由根目录 `signing.properties` 驱动，keystore `app/signing/clipdown.jks` **不入 git——务必备份（密码 clipdown2026，丢失无法升级签名）**；R8 反射 keep 见 proguard-rules.pro（ViewTree* 宿主绑定）
- adb：需非沙箱执行；装机后 `appops set com.clipdown.app SYSTEM_ALERT_WINDOW allow` + `settings put secure enabled_accessibility_services ...`；服务 `am start-foreground-service -n com.clipdown.app/.floatwindow.FloatingWindowService`
- 调试：`ACTION_DEBUG_PHASE`（--es phase parsing|parse_ok|... [--ei percent N]）直接驱动气泡状态机

## 发布与更新（远程快速更新链路）
- **版本号唯一来源**：根目录 `version.properties`（`versionCode`/`versionName`），`app/build.gradle.kts` 读取它；**不要在别处再写死版本号**。发布脚本会自动自增 `versionCode`（单调递增是更新器的唯一比较依据）
- **更新通道**：`gradle.properties` → `clipdown.updateRepo`（当前 = `Gorilla-Kevv/mobileSrcDownloadplugin`）+ `clipdown.apkAssetName`（默认 `clipdown-release.apk`）。构建时生成 `BuildConfig.UPDATE_MANIFEST_URL` / `UPDATE_APK_URL`，**改发布仓库只需改 gradle.properties，不必动代码**
- **清单地址用"最新发布固定链接"**：`https://github.com/<repo>/releases/latest/download/update.json` —— 不走 GitHub API，**不需要 token、不吃匿名速率限制**。代价是**发布仓库必须公开**：本仓库已于阶段 34 **转为 public**（用户决策），因此 APK 与清单直接发在主仓库
- **国内网络注意（阶段 34 实测）**：本机所在网络**直连 github.com 超时**（需代理），模拟器既连不上 GitHub 也到不了主机代理 → 应用内更新在模拟器上必然失败；国内测试机若拉不到清单，把 `clipdown.updateManifestUrl` / `clipdown.updateApkUrl` 指向国内可达托管（Gitee Releases / 阿里云 OSS / 腾讯云 COS / 自建静态服务）即可，**无需改代码**；`clipdown.updateRepo` 仅用于发布脚本
- **当前线上**：`v1.1.24（versionCode 29）`，2026-10-29 发布（含阶段 65：全套视觉美学体系全面换新【神秘 · 紫色 · 黑暗 · 隐私】· 黑曜秘境暗夜沉浸主题 · 神秘之眼与隐私护盾矢量桌面图标 · 256px 高清紫晶悬浮球 Logo · 全套暗黑紫夜 UI 组件与微光交互；阶段 64：通知栏下载进度断续与下载完成后仍显示正在下载中双重根因彻底根除 · 协程采样取消安全 join 杜绝竞态覆写 · 跨渠道通知注销与 ongoing/progress 彻底清除 · TaskDatabase 终态防护 · 前台服务超时优雅收口与孤儿自愈；阶段 63：内置浏览器终极加固 · 真机动态 Chrome 原生 UA 杜绝指纹异常 · 多窗口与 2FA 弹窗协同 · 一键清理重置风控缓存 · 600ms 高灵敏嗅特与 Via 浏览器手机免电脑提取指引；阶段 62：Instagram 登录两步验证死循环根除 · 默认开启电脑版完整客户端 · 多域名 Cookie 聚合检索 · 内置快捷导入 Cookie 兜底通道）
- **一键发布**：`bash scripts/publish-release.sh`（`--bump patch|minor|major|none`、`--notes "…"`、`--repo owner/name`、`--dry-run`、`--skip-build`、`--create-repo`）。它自增版本号 → `assembleRelease`+全量单测 → 生成 `build/dist/{clipdown-release.apk,update.json}`（含 sha256/sizeBytes）→ `gh release create --latest` 上传
- **每轮修复完成必须走这条链路发布**（用户约定 2026-10-09）：改完 → 本地 `assembleDebug`+全量单测 → commit → `bash scripts/publish-release.sh --bump patch --notes "…"` → 匿名校验清单 200 → 真机在「设置 → 关于与更新」或启动自检一键覆盖安装。本机跑法（PowerShell 调 Git Bash）：`& "C:\Program Files\Git\bin\bash.exe" -lc "cd /f/schoolCompWorks/clone/mobileSrcDownloadplugin && bash scripts/publish-release.sh --bump patch --notes '…'"`（约 2 分钟，含 R8）。**发布后记得把脚本自增的 `version.properties` 提交入库**
- **CI**：`.github/workflows/release.yml`（手动 dispatch 或 `git tag v1.0.3 && git push origin v1.0.3` 触发）。需配置 secrets `KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`，否则落回 debug 签名 → 老用户无法覆盖安装
- **应用内更新**：`update/UpdateCenter`（进程级单例状态）+ `UpdateRepository`（拉清单比对 versionCode）+ `ApkInstaller`（下载 → FileProvider → 系统安装器）+ `UpdateUi`（设置页「关于与更新」区块 + 启动提示框）。Manifest 需 `REQUEST_INSTALL_PACKAGES` 与 `${applicationId}.fileprovider`（路径见 `res/xml/file_paths.xml`）
- **覆盖安装前提**：新旧 APK **签名一致**（同一 keystore）。签名一致时系统原地升级、保留数据；不一致会提示"应用未安装"
- 单测：`:app:testDebugUnitTest`（更新清单契约 4 例）

## UI 设计体系（阶段 35 重构，改界面必读）
- **令牌层**：`ui/theme/Color.kt`（语义色：Brand600/BrandContainer、Neutral*、TextPrimary/Secondary/Tertiary、Success/Warning/Danger/Info 各含 `*Fg` + `*Container`）、`ui/theme/Theme.kt`（Spacing 4/8/12/16/20/24、Radius card18/control12/thumb14/pill999、10 级排版、M3 Shapes）。取用方式：`AppTheme.spacing.lg` / `AppTheme.radius.card`
- **硬规则**：**页面里禁止再写裸 `Color(0xFF...)` 与随手 dp 值**，一律引用令牌或共享组件；这样多屏才会看起来是同一套设计
- **共享组件** `ui/components/AppUi.kt`：`PageHeader`（页面标题+副标题）、`AppCard`（白底+1dp 描边+18dp 圆角，**不用阴影**）、`CardHeader`、`Hairline`、`StatusPill`（语义胶囊）、`AppChip`、`KeyValueRow`、`NoticeBar`（错误/警告/成功提示条）、`PrimaryButton`/`SecondaryButton`、`SettingSwitchRow`、`EmptyState`、`GroupLabel`、`VSpace/HSpace/HGroup`
- **版式规则**：页面 = `PageHeader` + 若干 `AppCard`，卡片间距 `spacing.md`、卡片内边距 `spacing.lg`、卡片内行用 `Hairline()` 分隔；列表/表单左右边界统一 `spacing.screen`
- **主题策略**：默认**浅色优先**（`ClipDownTheme(darkTheme = false)`）——悬浮气泡与弹窗沿用深色磨砂玻璃，强跟随系统深色会让弹窗浅色块与页面深色底混搭；深色方案已在 `Theme.kt` 备好，需要时把默认值改为 `isSystemInDarkTheme()`
- **本轮未动**：`floatwindow/`（`ClipPopupContent`/`BubbleBar`）是独立的深色玻璃层，语义色值与令牌一致，若要统一需单独一轮

## 状态
- 当前：**阶段 65 已完成并发布正式版 v1.1.24（versionCode 29）**——全套视觉美学体系全面换新【神秘 · 紫色 · 黑暗 · 隐私】：①应用桌面图标重塑：矢量护盾外壁 + 深渊内切面 + 神秘之眼 (Mystic Iris) + 能量钻石光眸 + 锐利下行流光，冷启动底色置为 `#FF09060E` 根治白屏；②悬浮球 Logo 图标全新渲染：256px 高保真无损黑曜底座与紫晶护盾透明图标，联动待机微光紫呼吸与神秘光环 (`HaloPurple #A855F7`)；③色彩令牌全面沉浸暗黑秘夜：以极夜 OLED 纯黑 `#09060E`、暗夜玄紫 `#130E1F`、发丝线 `#2E2048` 与高对比度高亮白字 `#F5F3FF` 重塑全局主题；④组件与各屏视觉统一：`ClipDownTheme` 默认开启全沉浸暗黑模式，全套组件微光感知，全量 153 例单测全绿，Release APK 1.87MB。
- 上轮：**阶段 64 已完成并发布正式版 v1.1.23（versionCode 28）**——通知栏下载进度断续与下载完成后仍显示正在下载中双重根因彻底根除：协程安全 join、跨渠道通知注销、TaskDatabase 终态防护与孤儿自愈。
- 上轮：**阶段 63 已完成并发布正式版 v1.1.22（versionCode 27）**——内置浏览器终极加固：真机 Chrome 拟真 UA、多窗口与 2FA 弹窗协同、一键重置风控缓存、Via 浏览器免电脑提取指引。
- 上轮：**阶段 62 已完成并发布正式版 v1.1.21（versionCode 26）**——Instagram 登录两步验证死循环根除 · 桌面端 UA 直显 · 多域 Cookie 聚合嗅探与弹窗快捷导入：①根因排除：针对 Meta 移动网页版根路由 `/` 渲染 404 PolarisErrorRoot 并无限回跳登录页的致命缺陷，Instagram 登录默认切为 Desktop 电脑版 UA，移除 `?next=%2F` 陷阱参数，两步安全验证后直通桌面版 Feed；②多域名 Cookie 聚合检索：`getAggregatedCookies` 扫描并合并 `instagram.com`、`www.instagram.com`、`i.instagram.com`、`accountscenter.instagram.com` 等所有子域，确保 `sessionid` 100% 捕获；③内置「粘贴 Cookie」备用通道：提示栏嵌入快捷操作，一键读取剪贴板并校验入库；④全量 152 例单测全绿，Release APK 1.89MB。
- 上轮：**阶段 61 已完成**——Instagram 内置浏览器登录白屏根除 · 访客追踪空 Cookie 拦截清洗 · 杜绝无会话风控误判与全链路一键解除冷却：①引入 `androidx.webkit:webkit:1.12.0` 并剥离 `X-Requested-With` 反爬标记，修正登录入口路由为带有 `next` 的 `https://www.instagram.com/accounts/login/?next=%2F`，放行 SSL 证书与拦截外部 DeepLink，提供桌面/移动 UA 切换与系统浏览器通道；②`CookieStore.isValidSessionCookie` 严格校验 Instagram `sessionid=` 等核心令牌，拒绝访客 `mid/csrftoken` 充数，`get()` 自动清理历史伪 Cookie；③`InstagramParser` 仅对真实持有 `sessionid` 的限流计入风控，杜绝无 Cookie 试用被误锁 10 分钟；④悬浮窗 `ClipPopupContent`、首页 `HomeScreen` 与设置页 `SettingsScreen` 全链路提供一键「解除冷却」与直接重试入口。
- 上轮：**阶段 60 已完成**——前台服务常驻通知三段式统计与实时动态感知刷新：①`foregroundNotification` 与 `updateForeground` 采用三段式展示——`正在下载 N · 已完成 M · 失败 K`；②`DownloadEngine` 挂载 `sessionCompleted`/`sessionFailed` 计数，入队、进度、完成、失败全生命周期刷新通知；③全部下载完成自动转为非 ongoing 终态通知，可直接滑除；④下载页副标题对齐三段式统计。
- 上轮：**阶段 59 已完成**——落地内置浏览器快速一键登录与凭据自动捕获 (WebLoginDialog)：①全屏直角 WebView 官方登录沙箱，自动轮询嗅探各平台登录令牌；②检测到登录成功即刻写入本地 `CookieStore` 并触发 `IgRiskGuard.reset()`，自动关闭弹窗无需手动复制；③设置页「登录态增强」卡片重构为直观状态药丸 + 直角一键登录按钮 + 高级折叠文本框；④首页解析受阻时直接弹出「打开内置浏览器一键登录」按钮，登录后自动重试解析。
- 上轮：**阶段 58 已完成**——彻底修复 Instagram Reel 视频解析被识别为图片及图片仅有一半的问题：①确立视频类型不变性 (Reel Type Invariance)，Reel 链接严禁降级为 25KB 封面缩略图，提取失败诚实抛出包含 Cookie/远端解析指引的异常；②`ownCodeSegment` 修复防语言/国家代码误截断正帖 JSON；③WebView UA 切换为真实移动端 UA 防 Meta 桌面风控登录墙；④WebView 挂载 `installIgHook` 拦截异步 GraphQL/REST 请求；⑤轮询探针禁止因 `image_versions2` 早退；⑥单测增至 145 例全绿。
- 上轮：**阶段 57 已完成**——彻底修复清理应用缓存/数据后悬浮球消失问题（启动与生命周期自愈拉起服务 + 智能授权引导弹窗 + 首页直角警告卡片 + 设置页联动跳转）。
- 上轮：**阶段 56 已完成**——彻底修复 Instagram 媒体下载传输中断与半截截断黑图硬伤：①落地 `MediaIntegrityValidator` 校验文件大小及 JPEG (SOI/EOI)、PNG (IEND) 物理签名，杜绝半截黑图静默落库；②`HttpFileDownloader` 强化 Content-Length 断言及 `ProtocolException` 统一转译，补齐移动端拟真 UA；③`DownloadEngine` 联动 Range 续传重试与坏块清理；④`InstagramParser` 优化原图清晰度选举与 UA 注入。
- 上轮：**阶段 55 已完成并发布 v1.1.14（versionCode 19）**——下载记录页视觉美化与四方形组件重构（完全摒弃圆角，统一采用 RectangleShape，含卡片/缩略图/状态标签/进度条/操作钮/确认弹窗）+ 真实本地物理路径展示（等宽字体/防溢出/一键复制入剪贴板）+ 图集存储目录汇总与子项明细路径。
- 上轮：**阶段 54 已完成并发布正式版 v1.1.13（versionCode 18）**——识别/下载过链接的重复下载提醒全链路恢复 + 发布脚本 aapt2 强制自检防版本错位（解决手机端 1.1.12 更新循环提示）。
- 上上轮：**阶段 53 已完成并发布 v1.1.10（versionCode 15）**——①预览缩略图全链路打通：解决 `DownloadController` 丢弃 `coverUrl` 的硬伤，引入 `coil-video:2.7.0` 与全局 `ImageLoaderFactory` 解码视频首秒帧，并在 `DownloadsScreen` 与相册画廊配置本地直接加载与占位兜底；②内置视频播放器现代化重构：彻底移除 Android 2008 年代原生 `MediaController`，打造 Compose 原生居中磨砂玻璃胶囊操作钮 + 底部渐变进度滑块与等宽时间戳 + 单曲循环切换 + 3.5s 智能沉浸显隐动画。
- 上轮：**阶段 52 已完成并发布 v1.1.9（versionCode 14）**——Instagram 解析失败多通道全面修复与风控误锁根治：①shortcode 换算 numeric media ID (pk) 支持官方原生移动端 REST `/api/v1/media/{id}/info/` 端点，无 LSD / doc_id 风险；②GraphQL 2026 结构适配：支持 `xig_polaris_media` 与 `if_not_gated_logged_out`，自动补齐缺失的 `csrftoken`，补全 `X-Requested-With`、`X-FB-Friendly-Name` 防止被 302 重定向到登录页；③WebView 增强：轮询探测增加 `image_versions2` / `xig_polaris_media` 避免图集帖白等超时，DOM `<video src>` / `<source>` 直链兜底，/reel/ 与 /p/ 双路径探测；④`CookieStore` 联动 `IgRiskGuard.reset()`，设置页更新 Cookie 即刻清除冷却，无需等待 10 分钟。已成功生成正式 Release 资产与更新清单
- 上轮：**阶段 51 已完成（含在 v1.1.9）**——①首页下载交互反馈与防重复点击：Toast + Success 状态条 + 按钮动态切换为「重新下载」与「查看下载任务」（直跳下载管理页）②下载记录真实删除确认弹窗：单任务与批量均弹窗确认，明确提示彻底删除本地文件与记录；升级 TaskRepository.deleteTaskFile 全格式兼容 ③下载列表缩略图直观预览：单条与图集组卡横排画廊展示 ④内置全屏图片与视频媒体浏览器（MediaViewerDialog）：全屏黑底沉浸视图，多图滑动与双击/捏合手势缩放，视频原生 VideoView 硬件加速播放与控制条，带应用内删除/外部分享/系统应用打开
- 上轮：**阶段 50 已完成并发布 v1.1.8（待真机复验）**——开合动画治卡顿：expandVertically 布局动画（逐帧测量+窗口 resize+LazyColumn 重测）改为**渲染层裁剪揭示**：内容一次性按最终尺寸布局（窗口开/关各 resize 一次），`Animatable`+`foundation GenericShape((Path,Size,LayoutDirection)->Unit)` 逐帧改 clip 矩形 + graphicsLayer alpha 淡入；Compose 1.7.5 三坑（GraphicsLayerScope 无自由裁剪属性 / Shape object 实现报 overrides nothing 必须走 GenericShape / 构造 lambda 重载歧义需显式函数类型）见 PROGRESS 阶段 50
- 上轮：**阶段 49 已完成并发布 v1.1.7（待真机复验）**——①胶囊宽度 64→**52dp**（=气泡头像圆直径 `BAR_WIDTH_DP`，两侧与圆相切；窗口仍 64dp 宽保触摸区）②竖条开合加 `AnimatedVisibility`（fadeIn+expandVertically 240ms / fadeOut+shrinkVertically 200ms，以气泡贴合端为生长基准、barOnTop 镜像；退出期用 `lastBarState` 续渲染防空白；`syncBubbleWindow` 收起态保持延伸方向到动画播完防跳边）
- 上轮：**阶段 48 已完成并发布 v1.1.6（待真机复验）**——竖条按用户参考图重设计：气泡与胶囊**叠合**（BubbleWindowContent 改 Box：胶囊先画垫底、气泡后画压顶，圆心落胶囊平边、重叠 32dp=BAR_OVERLAP_DP）；胶囊靠气泡端直角、另一端半圆（barOnTop 镜像）；气泡侧内边距 40dp 保护 64dp 触摸区；面板头部下载图标、底部改**深色圆形 ×**（=关闭气泡，收起面板仍点气泡 toggle）；syncBubbleWindow 去 gap、barFull 360→400。另：真机"检查更新 connection closed"定性为设备 VPN 未覆盖 github.com 的环境问题（用户指示不处理）
- 上上轮：**阶段 47 已完成并发布 v1.1.5（待真机复验）**——①竖条/面板统一纯色胶囊底板（64dp、16dp 圆角、GlassBase 深底+描边，Mini/图集/面板共用；解决真机浅色页面上部件浮空看不清）②IG 混合图集（图+视频）解析修复：GraphQL 通道补 `carousel_media`/`edge_media_to_sidecar_to_children` 提取 + 正帖节点内递归兜底（取证：游客态 embed/帖子页均已是 JS 空壳，匿名通道全线失效，带 Cookie 的 GraphQL 是单帖唯一通道；详见 PROGRESS 阶段 47）
- 上轮：**阶段 46 已实现完成并发布 v1.1.4（待真机复验）**——长按 700ms 呼出多功能面板（64dp 竖条同窗口形态）：头部"下载 N / 已完成 M"+聚合进度条（Σ已下载/Σ总），任务按 taskId 逐行实时刷新（平台色点+类别+百分比/合并中/失败原因），底部「关闭气泡」（危险色，`closeBubble()` 下沉为面板按钮）+「清空已完成」，点已完成行打开文件；气泡相位改显**聚合**进度（多路并发不再互相打回）；面板不自动超时收起、全部完成后 3s 自动收起（行保留可再长按查看）；Panel 态新图集不抢面板→计 pendingCount；旧"下载详情居中卡"（`PopupUiState.Downloads`）删除。**气泡手势总表（覆盖阶段 43）**：单击=识别剪贴板／双击=跳主界面／拖拽=移动／**长按=多功能面板**／面板内按钮=关闭气泡。详见 PROGRESS 阶段 46
- 上轮：**阶段 45 完成**——①弧线光环间隙真根因：`drawArc` 不传 `topLeft/size` 就按 64dp 画布取半径（只有 `drawCircle` 分支受 radius 控制），三处弧线已统一带 rect 与头像同半径；②X 敏感内容标记/较老推文改走**第三方镜像 fxTwitter 兜底**（`api.fxtwitter.com/i/status/<id>` 免登录，主机实测给 pbs 直链；官方 syndication/oEmbed/老 embed 页实测都拿不到）。X 失败文案仍按 404/敏感/普通三态分诊
- 上轮：**阶段 44（真机二轮反馈）完成**——①光环内叠 1dp 无缝融合 ②服务重启过滤 SharedFlow replay 的旧链接（重开气泡不再弹"再次解析"确认卡）③X 图片推文：实测 tweet-result 对近期推文 `photos[].url` 正常、`photo_info` 已被上游移除、老/删除推文 404、**敏感内容标记推文 200 但不下发媒体**——已按 404/敏感/普通三态给文案+分诊日志（oEmbed/embed 页实测拿不到 pbs直链，均不可作兜底）④`IgRiskGuard` 新增 `countsFailure`（缺 Cookie 传 false 不计入冷却，单篇链路已接，主页链路原有关前置检查）——未配 Cookie 连测 reel 不再误锁 10 分钟
- 上轮：**阶段 43（气泡光环紧贴 + 长按关闭气泡）完成**——①`BubbleCircle` 光环半径改为「logo 半径 + 半个描边宽」（原按 64dp 画布内缩，环与头像间有约 2.3dp 空隙），logo 尺寸与环半径共用 `BUBBLE_LOGO_SIZE_DP=52f`；②View 级触摸加长按判定（700ms）：未移动即 `closeBubble()`（收弹层→`stopForeground`→`stopSelf`+Toast+`setFloatEnabled(false)` 防自动复活），拖拽/抬手/取消均取消定时器，长按后吞掉单击双击。气泡手势总表：**单击=识别剪贴板／拖拽=移动／双击=跳主界面／长按=关闭**
- 上轮：**阶段 42（真机测试 9 项反馈修复）完成**——①小红书免登录定性（真机实测可解析，loginRequired=false，Cookie 入口与 loginRequired 解耦）②竖条 64dp 定宽修显示不全 ③多图默认全选/取消全选展开（defaultSelection 四处接线）④首页解析补 UrlUtil.extractFirst 与气泡链路对齐 ⑤已解析链接弹竖条"再次解析"提醒（带抑制窗口）⑥气泡右下角徽标取消 ⑦竖条收回（× 按钮 + 触摸分区恢复）⑧下载页类别按 mimeType（单条不成组/视频/图集）⑨居中弹窗去毛玻璃去投影精简化。详见 PROGRESS 阶段 42，**待真机复验**
- 上轮：**阶段 41（IG / X 博主主页解析）完成 X 侧实测、IG 侧代码就绪**——① 新发现 **X 主页可免登录**：`syndication.twitter.com/srv/timeline-profile/screen-name/<handle>` 的 `__NEXT_DATA__` 给 100 条时间线 + 完整 user 统计（旧文档"X 无公开时间线"结论作废），模拟器实测 Elon Musk 主页 100 条渲染正常、点开视频篇拿到 3 档 mp4 并**实际下载落盘**；② IG 主页确认**无免登录路径**（`web_profile_info` 429、主页 HTML 是登录墙），已实现三通道 `InstagramProfileParser` + 单测，待有效 Cookie 校准；③ **IG 冷却抽成 `IgRiskGuard` 共享保险丝**（单篇与主页共用失败计数，缺 Cookie 不计入）
- 待复验：**XHS 主页回归未通过——是会话失效不是代码回归**。同一份 Cookie 从 291KB/32 篇 SSR 退化为 40KB 登录墙（主机 curl 同样失败，见坑 28），需重新导出 Cookie 后复验
- 阶段 40（加载更多突破 3 页封顶）：根因是 WebView 没有视口（`innerHeight=0` → IntersectionObserver 永不触发）；`measure+layout` 补真实视口 + **去掉容器 `maxHeight`、改滚 window** + 阶梯扫动；修掉 `evaluateJavascript` 数字返回值被按 String 强转导致提前收口的 bug。实测单次 35 页、会话累计 272 篇
- 已解除的边界：~~单次 WebView 会话约 3 页封顶~~（阶段 39 记录，阶段 40 已解）。剩余上限是 `maxPosts=(pages*60).coerceIn(120,360)`（约 360 篇/账号），且每轮从首屏重扫靠 id 去重——效率折损，非功能缺陷
- 阶段 39（加载更多链路首版跑通）：桌面 UA + XHR 钩子回收 `user_posted` 分页 JSON；修掉 **debug 包继承 release 签名**这个阻塞级构建 bug（阶段 38 的编译/验证因此实际未通过：`ParserEngine` 缺 `webFetcherScroll` 形参、`ProfileScreen` 缺 `SecondaryButton` import，一并补齐）；新增真机夹具与 `scripts/check-inject-js.js`
- 阶段 38（主页多标签 + 进程重启恢复）：功能与实测成立（标签/勾选恢复 ✓），但**当时未真正编译通过**，阶段 39 已补齐
- 阶段 37（小红书主页真实跑通）：桌面 UA + Cookie 取 SSR（32 篇）、三入口、4 个真 bug（短链登录页陷阱 / jsonField 截断 / 无引号数字 / 转义还原）
- 阶段 31-33（小红书笔记链路）：`xhslink.cn` 路由、短链 `<a href>` 展开、移动端 UA/结构适配、fileId 去重、失效/登录墙守卫；**图集真实下载仍需 Cookie 或真机**（反爬含客户端指纹，见坑 17）
- 阶段 30（release 装机冒烟）：release 装机成功、R8 无运行时崩溃；下载闭环受模拟器出口 IP 限制未完成（环境，非回归），下载引擎由 30 例单测兜底。踩坑 14（`adb shell cat` 二进制 CRLF 翻译损坏 DataStore proto）
- 验收标准：`assembleDebug`/`assembleRelease` 全绿 + `:parser:test`（105）+ `:downloader:testDebugUnitTest`（40）+ `:app:testDebugUnitTest`（7）+ 模拟器/真机关键链路实测 + release 冒烟
- 下一步：①**真机复验阶段 61 内置浏览器一键登录与风控解除** ②**IG 主页用有效 Cookie 实测校准**（`ig_profile_api.json`/`ig_profile_page.html` 目前是结构样本，需换成真响应；严守坑 13 纪律 + 坑 28 不复用会话）③**XHS 重新导出 Cookie 后复验主页回归**④B 站/微博主页接入（免登录，可用来验证多平台主页框架；B 站项目内已有 WBI 签名代码）⑤加载更多效率优化（透传 `cursor`，避免每轮从首屏重扫；或放宽 `maxPosts`）⑥IG 风控恢复后复测阶段 25 ⑦keystore 备份提醒：`app/signing/clipdown.jks`（密码 clipdown2026）务必备份
- 文档：PROGRESS.md 全阶段记录（阶段 1-61，头部进度看板，含旧编号对照，阶段条目严格升序）；PLAN.md（阶段 14-18 计划，已全部实现）；docs/06（博主主页功能设计）
- 本次文档更新时间：10.29 18:40 [BY Antigravity]
