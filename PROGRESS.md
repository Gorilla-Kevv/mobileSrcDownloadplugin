# PROGRESS

## 进度看板
- 当前正在开发任务：阶段 33 完成（小红书视频帖结构取证 + 真实夹具；masterUrl 实测 200/video/mp4/支持 Range）
- 下一阶段任务：用户提供小红书 `web_session` Cookie（或改用真机）→ 三例一起复测：单图 1 张 / 多图 3 张 / 视频帖无竖条直接自动下载
- 可提前进行的任务：阶段 29 真机复测（X 图集竖条 / 气泡下半屏向上生长 / 再点收起 / IG 冷却文案）；阶段 25 之 IG 图集竖条（风控恢复后）；X 单视频下载闭环（家宽绕过 X 404）
- 未完成的任务：小红书设备端真实成功下载（环境反爬，需 Cookie/真机）；阶段 30 下载闭环冒烟（需真机）；IG 风控恢复后阶段 25 复测
- 测试基线：parser **58 例** + downloader 30 例全绿
- 说明：BY ZCode（本项目全程 ZCode 系 agent，含前序会话）；历史"修复 8/9/10/11/12"已并入对应阶段条目（8→16、9→18、10 系列→22-25、11→28、12→29）
- 旧编号对照：原阶段 9/10 时间交错重排为 10/11；原 12-18→13-19；原 19/20→20/21；原 20 返工→21；原修复 10 系列→22-25；原 21a→26、原 21b→27；原修复 11→28；原修复 12→29；本 session 新增阶段 30（release 装机冒烟+新坑 14）
- 本次文档更新时间：10.06 11:30


## 阶段 1 应用完整实现（三模块/解析内核/下载引擎/四通道/悬浮窗） [计划时间：09.25 20:00 BY ZCode][完成时间：09.26 17:36 BY ZCode]
- 已完成：
  - 开发环境搭建（JDK17 + SDK + Gradle 8.9 + Wrapper + 环境变量固化脚本 `C:\AndroidDev\build.bat`）
  - 三模块工程骨架（app / parser / downloader）与 Manifest、主题、图标、无障碍配置
  - 解析内核：UrlUtil、PlatformRegistry(SPI)、ParserEngine（三级降级）、RemoteResolver、M3u8 解析、10 个解析器（含 B站 WBI 签名）
  - 下载引擎：队列并发、Range 续传、HLS 分片+AES-128、DASH 合并、SQLite 持久化、MediaStore 入库、前台服务+通知
  - App 层：四通道剪贴板获取（前台/借道/无障碍/分享）+ LinkCenter 去重、悬浮球 + 毛玻璃弹窗、三页面 UI
  - 设计文档 README.md + docs/01~05
- 未完成：git 初始化与提交；单测；release/签名；真机回归
- 改动文件：全仓库为新建（约 60 个源码/资源/文档文件，无历史基线）
- 测试结果：`:app:assembleDebug` 全绿（60 任务），无编译错误，lint 0 报错；产物 `app-debug.apk` 17.33 MB；未做运行时验证
- 风险：平台页面结构变更会导致对应解析器失效（已带 resolverId 便于定位）；Android 10+ 后台剪贴板为系统硬限制，依赖无障碍/分享通道兜底；ViewTree 反射调用依赖 AndroidX 运行时存在
- 下一阶段入口：读 `HANDOFF.md` 的「下一步」清单，从 `git init + 首次提交` 开始；新会话只带 `HANDOFF.md` + `PROGRESS.md`
- 本次文档更新时间：10.06 01:45

## 阶段 2 开发环境迁移至 F:\AndroidDev [计划时间：09.26 10:00 BY ZCode][完成时间：09.26 17:42 BY ZCode]
- 已完成：开发环境整体迁移至 `F:\AndroidDev`（JDK17 / SDK / Gradle 8.9 / 依赖缓存）；用户级环境变量与 PATH 已确认指向 F 盘；`local.properties` 的 `sdk.dir` 与统一构建脚本 `F:\AndroidDev\build.bat` 已更新；清理了迁移带入的临时目录（wtmp / probe_tmp / cmdline-tmp）与临时脚本，保留 `install_sdk.bat`、`setup_env.ps1`、`repo.xml`、`sdk\.sdk`（sdkmanager 缓存）
- 未完成：git 初始化与提交、单测、release/签名、真机回归（同阶段 1）
- 改动文件：`local.properties`（F 盘路径）、`F:\AndroidDev\build.bat`（重写指向 F 盘）、`HANDOFF.md` / `README.md` / `PROGRESS.md`（文档同步）
- 测试结果：`F:\AndroidDev\build.bat :app:assembleDebug` 全绿（60 任务），`app-debug.apk` 正常产出
- 风险：`F:\AndroidDev\sdk\.sdk` 为 sdkmanager 内部缓存目录，勿删除；再次迁移环境需同步改环境变量、`local.properties`、`build.bat` 三处
- 下一阶段入口：读 `HANDOFF.md` 的「下一步」清单，从 `git init + 首次提交` 开始
- 本次文档更新时间：10.06 01:45

## 阶段 3 git 初始化与首次提交 [计划时间：09.26 17:30 BY ZCode][完成时间：09.26 17:42 BY ZCode]
- 已完成：
  - `git init -b main`；首次提交 `670415f`（102 文件，11064 行）
  - 发现并修复误提交：`parser/bin/` 为 Kotlin 语言服务器生成的源码冗余副本（与 `src/` 逐字节相同），已从跟踪移除并在 `.gitignore` 增加 `bin/`，清理提交 `0aaec2f`（-21 文件 -2790 行）
  - 提交前安全检查：暂存清单无 `local.properties`/`build/`/`*.apk`/密钥文件
- 未完成：真机回归；`:parser` JVM 单测；release 签名 + R8 验证
- 改动文件：`.gitignore`（+`bin/`）；仓库现为 81 个跟踪文件，工作树干净，main 分支 2 个提交
- 测试结果：`git log`/`git status` 验证通过；无构建改动，不影响 assembleDebug
- 风险：无（纯版本控制操作）；注意本机 PowerShell 为旧版，不支持 `&&` 与 heredoc，批命令需用 `;` 与 here-string
- 下一阶段入口：按 `HANDOFF.md`「下一步」优先级推进——真机回归（需用户配合）或先做 `:parser` JVM 单测（可独立执行）
- 远程仓库：`origin = https://github.com/Gorilla-Kevv/mobileSrcDownloadplugin.git`（私有，gh CLI 账号 Gorilla-Kevv），main 已跟踪 origin/main 并推送 3 个提交
- 本次文档更新时间：10.06 01:45

## 阶段 4 AVD 模拟器环境 + UI 冒烟测试 [计划时间：09.27 15:00 BY ZCode][完成时间：09.27 18:04 BY ZCode]
- 已完成：
  - 模拟器环境：`F:\AndroidDev\sdk` 补装 emulator 37.1.11 + `system-images;android-34;google_apis;x86_64`；新版 android CLI 有解压崩溃与包名拆分 bug，改用 classic cmdline-tools 12.0 安装；avdmanager 因 SDK 根解析问题建 AVD 失败，改为**手写 AVD 配置**（`C:\Users\kevin\.android\avd\clip34.avd\config.ini` + `clip34.ini`，pixel_6 / 1080x2400 / 3GB RAM）
  - 启动方式：`emulator -avd clip34 -no-window -gpu swiftshader_indirect -no-snapshot -no-audio`（约 25s 完成 boot）
  - APK 安装：`adb install -r` Success；权限预授权：`appops set ... SYSTEM_ALERT_WINDOW allow` + `settings put secure enabled_accessibility_services`
  - UI 冒烟：首页（粘贴框/权限卡/平台列表）、下载页、底部导航渲染全部正常；悬浮球激活
  - E2E 链路：输入 B 站链接 → 解析出真实标题/缩略图/资源选项 → 下载 → MediaStore 入库 `Movies/ClipDown/` → 下载页「已完成」+ 前台服务通知（channel=clipdown_download），全程无崩溃
  - 修复 1：`ClipDownApp` 接入 `ParserEngine` logger（原来解析失败原因被静默吞掉）
  - 修复 2：`BilibiliParser` 增加 cookie 预热（访问视频页收集 buvid3 等 Set-Cookie）+ `fetchWbiKeys` 失败正确降级（原返回空串导致空密钥签名）+ nav/playurl 风控响应诊断日志
- 未完成/未解决：
  - B 站 playurl 对本环境持续风控：view 接口正常，nav 无 wbi_img、playurl 返回 404 HTML 错误页。cookie 预热不够，需（a）用户在设置页注入 B 站登录 cookie（SESSDATA），或（b）配置远端解析服务。属 IP/指纹级风控，无状态解析器无法绕过
  - `GenericParser` 把 B 站外链播放器 HTML 存成 .mp4（6.5KB 假视频），建议后续加 Content-Type / magic bytes 校验
  - 其余平台（X/抖音/小红书/微博等）解析器未在模拟器实测
- 改动文件：`app/.../ClipDownApp.kt`（+logger）、`parser/.../BilibiliParser.kt`（预热/降级/诊断）、`HANDOFF.md`（环境/git 状态同步）；PROGRESS.md 本节
- 测试结果：`assembleDebug` 三次全绿（增量 48-50s）；模拟器实测解析/下载链路通过
- 风险：沙箱内 adb 守护进程跨调用不持久（表现为 device offline），adb 命令须非沙箱执行；PowerShell 二进制重定向会损坏截图，须 `screencap 到 /sdcard + adb pull`
- 下一阶段入口：真机回归（B 站风控在真实手机+家庭宽带 IP 下大概率消失，可验证 BilibiliParser 专属路径）；或先做 `:parser` JVM 单测；或注入 SESSDATA 后在模拟器复测 B 站全链路
- 本次文档更新时间：10.06 01:45

## 阶段 5 模拟器代理 + YouTube 全链路实测 [计划时间：09.27 17:00 BY ZCode][完成时间：09.27 18:45 BY ZCode]
- 已完成：
  - 模拟器代理：启动参数 `-http-proxy http://10.0.2.2:7890`（10.0.2.2=宿主回环→Clash）+ Android 全局代理 `settings put global http_proxy`，YouTube/Google 实测可达
  - **YouTube E2E 全通**：Piped 中继解析（实例探测：kavin=526✗、adminforge=301✗、private.coffee=200✓、reallyaweso=502✗）→ 标题/缩略图/资源选项渲染 → 360p（googlevideo itag-18）下载 → MediaStore 入库 11.3MB → **文件头验证 `ftyp mp42` 为真实 MP4** ✅
  - 悬浮窗毛玻璃弹窗实战自证：自动抓取剪贴板链接→平台徽标→解析中→结果卡，15s 倒计时自动收起
  - 修复 3：`YoutubeParser` 的 HLS 过滤条件错误（Piped 实际 format 为 `HLS`/`MP4`/`MPEG_4`，原代码比较 `MIME_TYPE_VIDEO_HLS` 永不匹配 → master.m3u8 被当直链下载，产出 489B 假 mp4）；现正确标记 `isPlaylist=true` 路由到 M3u8 下载器
  - 修复 4：`DownloadController.enqueue` 增加 `.m3u8` URL 后缀兜底路由（防解析器漏标记）
- 已知问题（新）：
  - Piped LBRY 镜像内容错位：LBRY/LBRY HLS 流（odycdn）指向 10 小时长视频（v0.m3u8 共 3620 段），与 213s 原视频不符——上游数据质量问题，引擎行为正确（分片/相对 URL/变体选择均正常）。改进方向：用 Piped `duration` 字段做时长 sanity check
  - 模拟器 UI 自动化坑：Compose 卡片高度随键盘/重布局漂移 ~132px，固定坐标点击会误触资源 chip；稳定做法=收键盘→截屏实测坐标→点击
- 改动文件：`parser/.../YoutubeParser.kt`（HLS 标记）、`downloader/.../DownloadController.kt`（m3u8 兜底）
- 测试结果：`assembleDebug` 全绿（55s）；360p 产物 11,829,048B 且 `ftyp mp42` 验证通过；HLS 引擎对正常播放列表的分片下载/变体选择已验证（用错位内容跑通了全流程）
- 下一阶段入口：真机回归（B 站专属解析+家宽 IP）；`:parser` JVM 单测；可选：下载产物 magic bytes 校验、时长 sanity check
- 本次文档更新时间：10.06 01:45

## 阶段 6 Instagram / X 模拟器实测 [计划时间：09.27 18:50 BY ZCode][完成时间：09.27 19:12 BY ZCode]
- 已完成：
  - **X 全链路通过**：Chrome 复制真实推文链接（@Watase_Yuzuki 图片推文）→ 读取剪贴板 → syndication 接口解析（结果标注「X (Twitter) · 平台公开接口」= OFFICIAL，guest token 推导正常）→ 原图下载 361KB → 落盘 `Pictures/ClipDown/`，**JPEG 魔数 `FF D8 FF E0` 验证通过** ✅；图片任务正确路由到 Pictures 目录
  - **IG embed 路径通过**：Chrome 复制真实 reel 链接（instagram.com/reels/DczssLA...）→ 免登录 embed 解析出原图与作者（@utamichann，悬浮窗弹窗路径独立验证），结果标注「Instagram · 平台公开接口」+ 降级提示「多图作品需要登录态才能全部获取」✅
  - 悬浮窗弹窗再次实战：剪贴板粘贴动作自动触发，X/IG 平台徽标、结果卡、15s 倒计时均正常
- 未完成/已知问题：
  - **IG 视频直链未获取**：该 reel 的 embed 页未暴露 `video_url`，需登录 Cookie 才能走 parsePrivateApi 拿视频多清晰度。Chrome/官方 App 的登录态无法共享给 App（Cookie 隔离），需用户从电脑浏览器导出 IG Cookie（F12 → Application → Cookies → 复制整串）粘到 App 设置页对应输入框
  - 用户手工测试遗留产物：`Instagram-a6a2.jpg` 900B 实为 WEBP（oEmbed 降级路径只拿缩略图 + 容器扩展名误标 webp→jpg）——改进方向：按实际字节定扩展名
  - X 视频推文（variants 多码率）未做 UI 实测（Chrome 盲操作复制失败率过高 + 截图内容审查拦截），该逻辑为纯 JSON 解析，交由 `:parser` JVM 单测覆盖
  - 无障碍服务在应用重装/重置后会失效（设置页显示"去开启"），后台剪贴板通道依赖它
- 改动文件：无代码改动（纯测试）；PROGRESS.md 本节
- 测试结果：X/IG 真实链接解析均成功，X 原图产物字节级验证通过
- 下一阶段入口：`:parser` JVM 单测（重点覆盖 X variants/IG embed/M3u8）；或用户注入 IG Cookie 后复测 IG 视频；或真机回归
- 本次文档更新时间：10.06 01:45

## 阶段 7 IG Cookie 注入 + 登录态解析攻坚 + IG App 安装 [计划时间：09.27 19:15 BY ZCode][完成时间：09.27 19:47 BY ZCode]
- 已完成：
  - Cookie 注入：用户提供网页版 Cookie（sessionid 等 9 项），adb base64 管道直写 `shared_prefs/clipdown_cookies.xml`（`/sdcard` cp 被 API34 沙盒拒，base64 方案可行）
  - **IG 登录态解析重写**：`?__a=1&__d=dis` 老接口 2026 已废（404 HTML）；帖子页 HTML 是 663KB JS 空壳（媒体全不在 HTML 里）；最终实现 **GraphQL 方案**：GET 帖子页取 LSD 令牌 → POST `/api/graphql`（doc_id=8845758582119845 + X-IG-App-ID + X-CSRF-Token + X-FB-LSD + jazoest）→ 解析 `data.xdt_shortcode_media`（video_versions/image_versions2/sidecar）
  - `HttpFacade` 新增 `postForm`（x-www-form-urlencoded）
  - **Instagram App 安装到模拟器**：APKPure XAPK（132MB，需浏览器 UA+Referer 绕 403），`install-multiple` base+mdpi 成功，ARM 翻译下正常启动到登录页
- 未完成/已知问题：
  - **GraphQL 返回 "Log in to continue"（error 1357001）**：常规页面承认会话（/accounts/edit/ 200 且 echo ds_user_id），但 /api/graphql 拒绝。怀疑 Clash 多节点分流导致 GET/POST 出口 IP 漂移（页面宽容、API 严格校验会话-IP 绑定）。jazoest 校验、fresh CSRF 均无效。**结论：IG 视频的网页 Cookie 路线在模拟器+代理环境不可行，需真机+家宽 IP 复验**（代码已就位，条件满足即生效）
  - App 内 Cookie 输入框在设置页底部（需滚动），用户没找到——后续可在首页权限卡下加"补充 Cookie"快捷入口
- 改动文件：`parser/.../InstagramParser.kt`（GraphQL 重写）、`parser/.../http/HttpFacade.kt`（+postForm）
- 测试结果：`assembleDebug` 三次全绿；IG App 可正常启动
- 下一阶段入口：用户在 IG App 登录后测「分享/复制链接 → 悬浮窗」真实场景；`:parser` JVM 单测；真机回归
- 本次文档更新时间：10.06 01:45

## 阶段 8 X 视频 + 小红书 + IG 真实场景实测 [计划时间：09.27 19:50 BY ZCode][完成时间：09.27 20:15 BY ZCode]
- 已完成：
  - **IG 真实场景全通**：用户登录 IG App → reel 分享 Copy link → 切回 App → 悬浮窗自动弹出解析出 @kedronji 自己的 reel（平台徽标/倒计时/原图）✅
  - **X 视频全链路通过**：宿主机批量探测 syndication 找到带视频的真实推文（astro_anil 2094077925989515691，PowerShell 版 guest token 推导同步验证）→ App 解析出 3 个 mp4 变体（视频·中码率/低码率×2，码率排序正确）→ 下载 9.8MB → **ftyp isom 真 MP4** ✅
  - **小红书实测**：真实 explore 链接（含 xsec_token）匿名访问被 404 墙（"你访问的页面不见了"），GenericParser 兜底抓到 404 页图片。与 loginRequired=true 设计一致，需用户注入 XHS 网页 Cookie（a1/web_session）
  - 发现 adb input text 对含 `?` 的 URL 会吞字符——长 URL 一律走分享通道（ACTION_SEND EXTRA_TEXT）
- 已知问题（新）：
  - 分享通道 openParse 疑似解析了输入框旧内容而非提交链接（弹窗徽标显示 Instagram 而非 X），需复查 ShareTargetActivity→LinkCenter→MainActivity 链路
  - 悬浮窗弹窗 15s 倒计时太短（自动化/手慢场景易超时），可考虑结果到达后重置计时
- 改动文件：无代码改动（纯测试）
- 测试结果：X 视频产物 10,241,500B ftyp isom 验证通过；IG/XHS 真实链接解析结论如上
- 平台覆盖总结：YouTube✅(视频) / X✅(图片+视频) / IG✅(图片+真实场景)、IG视频⏸(需真机家宽IP) / 小红书⏸(需Cookie) / 抖音·Facebook·微博·TikTok·B站专属解析 未测
- 下一阶段入口：用户注入 XHS Cookie 复测；`:parser` JVM 单测；真机回归
- 本次文档更新时间：10.06 01:45

## 阶段 9 小红书 Cookie 实测 + WAF 指纹定性 [计划时间：09.27 20:20 BY ZCode][完成时间：09.27 20:41 BY ZCode]
- 已完成：
  - XHS Cookie 注入（a1/web_session/webId 等，同 IG 的 base64 直写方案）；精简掉含引号的 `unread` 噪音项
  - **定位三层原因**：①二手 xsec_token（B 站简介抄的）被拒（300031 当前笔记暂时无法浏览）→ ②用 Cookie 访问首页可提取**第一方 token** 的笔记链接（宿主机验证 200 + urlDefault 出数据）→ ③**同一 URL 宿主机 curl 成功、App OkHttp 被拒**——定性为阿里云 WAF 拦 OkHttp TLS 指纹（acw_tc 预热拿到了也无效，非 Cookie/IP 问题）
  - XhsParser 增加 WAF 预热（acw_tc 种子）与请求诊断日志（保留，未来有用）
- 结论与待办：
  - **小红书网页解析需要 WebView 抓取方案**（真浏览器栈过 WAF 的 JS/指纹挑战）——跨 :app/:parser 架构改动（ParseContext 注入 webFetcher 能力），列入下一步；OkHttp 直连路线已判死
  - XHS Cookie 的 `unread` 值含 URL 编码 JSON（引号），注入前应过滤
- 改动文件：`parser/.../XiaohongshuParser.kt`（预热+诊断）
- 测试结果：`assembleDebug` 两次全绿；诊断链路完整（每次失败都能看到具体原因）
- 下一阶段入口：WebView 抓取方案设计 → 实现；或 `:parser` JVM 单测；或真机回归
- 本次文档更新时间：10.06 01:45

## 阶段 10 :parser JVM 单测（7 套件 34 例，修复 jsonField \u 转义） [计划时间：09.27 20:45 BY ZCode][完成时间：09.27 21:01 BY ZCode]
- 已完成：
  - **7 个测试套件 34 个用例全绿**（JUnit4，`:parser:test` 33s）：UrlUtil 8 / M3u8 4 / PlatformRegistry 6 / X 5 / Youtube 4 / Instagram 3 / Xiaohongshu 4
  - 真实数据夹具入库 `parser/src/test/resources/`：syndication_video_tweet.json（真实 API 响应+已知 token 答案 52qqmtfi7esad5b）、piped_streams.json（真实 Piped 响应）、xhs_note.html（真实笔记页 104KB）
  - **修复 5（真 Bug）：`HtmlUtil.jsonField` 不支持 `\uXXXX` 转义**——小红书 SSR 用 `\u002F` 表示斜杠，`[^"\\]+` 遇反斜杠即断导致整段匹配失败；pattern 改为 `(?:[^"\\]|\\.)+?` + unescapeJson 增加 `\uXXXX` 通用解码（IG 等平台同样受益）
  - **修复 6（小）：`UrlUtil.normalize` 未去除路径尾斜杠**（query 存在时 trimEnd 只作用于串尾）→ 路径 `trimEnd('/')`，链接去重更稳
- 改动文件：`parser/src/test/**`（8 文件+3 夹具）、`HtmlUtil.kt`、`UrlUtil.kt`
- 测试结果：`:parser:test` 全绿（34/34）
- 下一阶段入口：WebView 抓取方案（小红书）；真机回归；`:downloader` 单测（可复用 FakeHttp 思路）
- 本次文档更新时间：10.06 01:45

## 阶段 11 WebView 抓取实现 + IG reel 实测（基建完成） [计划时间：09.27 21:10 BY ZCode][完成时间：09.27 22:06 BY ZCode]
- 已完成：
  - **webFetcher 架构落地**：ParseContext.webFetcher 注入点（纯 JVM）→ App 层 `WebViewHtmlFetcher`（Cookie 注入 + Cookie 请求头双通道 / 桌面 UA / 无状态轮询 / 超时回传）
  - IG 解析降级链完整化：Cookie GraphQL → embed → **WebView 渲染页**（extractFromPageHtml）→ oEmbed（容器按扩展名修正 webp 误标 jpg）
  - WebView 渲染 **跑通**：reel 页 650KB→1037KB HTML 稳定抓取；轮询改为无状态定时抓取（不依赖 evaluateJavascript 回调链——原回调链被页面 JS 阻塞 38s 无响应，已改掉）
  - 单测 35 例全绿（新增 webFetcher 通道用例）
- **未解决（精确续接点）**：
  - WebView 渲染的 reel 页 **DOM 持续无媒体数据**（title=Instagram 非 Login 墙、页面 1037KB 存活增长，但无 video_url/og:video/video 标签）
  - 待排除清单：①`accounts/edit/` 登录态探针未触发（share intent 未引发新 parse，需查 LinkCenter 分发）②验证 sessionid 是否被 WebView 会话采用（document.cookie 看不到 httpOnly，需用页面 UI 判断）③尝试手机 UA + IG 移动版页面 ④IG 可能对非浏览器环境根本不给媒体（比对 saveinta 服务端账号池方案 → 自建中继 cobalt 是正解）
  - 用户问题结论已给出：套壳第三方站点可行但不推荐（脆弱+ToS 风险），自建远端中继（设置页已留接口）为正解
- 改动文件：`parser/spi/PlatformParser.kt`、`parser/core/ParserEngine.kt`、`parser/parsers/InstagramParser.kt`、`parser/parsers/XiaohongshuParser.kt`、`parser/parsers/InstagramParser.kt`（oEmbed 容器）、`app/clip/WebViewHtmlFetcher.kt`（新增，全程日志）
- 测试结果：`:parser:test` 35 例全绿；`assembleDebug` 绿；模拟器实测 IG reel 视频未通（如上）
- 下一阶段入口：按"未解决"清单逐项排除；或转向 `:downloader` 单测/真机回归
- 本次文档更新时间：10.06 01:45

## 阶段 12 悬浮窗复制触发修复 + 弹窗内下载闭环 [计划时间：09.27 22:30 BY ZCode][完成时间：09.27 23:39 BY ZCode]
- 用户需求：①复制链接后悬浮弹窗自动弹出；②弹窗半透明毛玻璃质感；③弹窗为顶级交互入口，下载全流程在弹窗内闭环，无需进主界面
- **定位到的 4 个真根因（全部修复）**：
  1. **`accessibility_service_config.xml` 的 `android:packageNames=""`（空串）= 空数组 = 不匹配任何包**——无障碍服务"已绑定但收不到任何事件"的总根因（框架语义：不写此属性才接收全部包）。删除该属性后事件流立即恢复
  2. **防抖为丢弃式**：复制瞬间事件风暴中首条事件先于内容出现，携带真实数据的后续事件全被 800ms 防抖吞掉 → 改为**尾部合并**（最后一次事件后静默 350ms 统一扫描）
  3. **Android 10+ 剪贴板后台读取被拒**（logcat 实证 `ClipboardService: Denying clipboard access`，无障碍服务也无豁免）→ 三段式策略：复制特征 Toast（"已复制/Copied"）→ 借道 [ClipGateActivity]（无障碍+悬浮窗权限持后台启动豁免）读剪贴板；窗口短促复制提示文本（IG Snackbar 类）兜底；剪贴板直读仅在偶有焦点时生效
  4. **ClipGateActivity 在 onResume 读剪贴板过早**：Android 12+ onResume 早于窗口焦点授予，必被拒（实测 `Displayed` 晚于 deny 700ms）→ 改在 `onWindowFocusChanged(hasFocus=true)` 读取
- **弹窗内下载闭环（新增）**：PopupUiState.Ready 增加 downloading/downloadPercent/downloadDone/downloadError；下载中停倒计时，进度条/合并提示（99%="正在合并音视频…"）/完成对勾/失败文案全部在弹窗内；毛玻璃强化（BLUR_RADIUS 28→56px、scrim 减淡）
- **引擎级修复（`:downloader`）**：DownloadEngine 此前**从不发出 MERGING/COMPLETED/FAILED 进度事件**（reportLoop 只发 DOWNLOADING 采样）→ execute() 在 updateStatus(MERGING)/markCompleted 后补发事件，runTask 失败终态补发 FAILED（文件早已落盘但 UI 永远停在"下载中 100%"的根因）
- **扫描噪音治理**：页面内容里的短链（t.co 等）是媒体跳转噪音（展开后落在媒体主机，提不出作品 ID）→ 窗口扫描通道跳过短链，完整 URL 由地址栏/复制气泡提供
- **弹窗状态保护**：视频播放会每 500ms 触发窗口内容变化 → 每 15s 去重过期后重复弹窗覆盖下载状态 → 服务级 `downloadActive` 标志集中守卫 showAndParse；下载完成后 10 分钟内抑制同一 URL 自动重弹（悬浮球转绿色"!"待处理态）
- **修复 7（小）**：`UrlUtil` URL_PATTERN 漏排除弯引号 `“”‘’`，推文标题里的 t.co 带尾引号被整段吃入 → 补进排除类
- 改动文件：`app/clip/ClipAccessibilityService.kt`（重写）、`app/clip/ClipGateActivity.kt`、`app/floatwindow/FloatingWindowService.kt`、`app/floatwindow/ClipPopupContent.kt`、`app/floatwindow/PopupUiState.kt`、`app/res/xml/accessibility_service_config.xml`、`downloader/engine/DownloadEngine.kt`、`parser/core/UrlUtil.kt`
- 测试结果：`:parser:test` 35 例全绿；模拟器实测全链路通过——Chrome 地址栏扫描自动弹窗 ✓、复制气泡扫描自动弹窗 ✓、悬浮球借道读取剪贴板 ✓（deny 日志消失）、弹窗内下载 16%→合并→完成→自动收起 ✓、完成后同链接不重弹 ✓、毛玻璃视觉确认 ✓；产物 `/sdcard/Movies/ClipDown/*.mp4`（10.2MB）落盘验证
- 遗留：IG 内复制场景的 Snackbar 借道通路已实现但未在 IG App 内实测（IG 视频解析本身待阶段 10 遗留解决）；X 解析偶发回落通用解析（guest token 波动，既有问题）
- 环境教训（重要）：**模拟器上 `adb install -r` 或反复 `settings put` 切换无障碍后，服务会出现"dumpsys 显示已绑定但事件永不派发"的假死态**——卸载重装后首次启用可恢复；彻底恢复需重启模拟器
- 本次文档更新时间：10.06 01:45

## 阶段 13 气泡交互重构 + IG Cookie 注入固化 [计划时间：09.28 00:30 BY ZCode][完成时间：09.28 01:14 BY ZCode]
- 用户四点需求全部落地（提交 `580b0d3`）：
  1. **气泡纯入口化**：头像 logo（用户图片裁切 256px，`drawable-nodpi/bubble_logo.png`）+ 待处理绿点；点击只展开迷你面板（`PopupUiState.Mini`：logo + "识别链接"按钮 + hint 提示），不再借道读剪贴板/不再自动解析/不再跳应用
  2. **跳转入口**：解析弹窗新增描边按钮「跳转至剪存应用」；"下载"更名"开始下载"
  3. **后台下载**：下载中点空白收起弹窗，任务在 DownloadService 继续；终态簿记与弹窗可见性解耦；collect 终态自终止（修泄漏）
  4. **识别记忆**：DataStore `seen_links`（StringSet，上限 400）持久化——已识别链接不再自动弹窗，仅迷你面板手动识别（绕过记忆 + gate force 提交）；`UrlUtil.normalize` 剥离 X `/mediaViewer` 变体，杜绝视频播放期间绕过记忆反复弹窗
- 附带修复：`ClipGateActivity` 独占任务栈（taskAffinity=""）消除跳转主界面闪现；剪贴板读取 200ms×3 重试；识别结果回调驱动迷你面板
- **IG Cookie 注入方法（固化）**：`adb root` → 写 `/data/data/com.clipdown.app/shared_prefs/clipdown_cookies.xml`（key=`instagram`，`k=v; k=v` 格式）→ chown u0_aXXX（uid=10204→u0_a204）→ force-stop 重启。**验证信号：WebView 抓取 title 从 "This content is unavailable • Instagram" 变为 "Instagram"**（页面 1037KB）
- 未解决：登录态下 WebView 渲染 reel 页 DOM 仍无媒体数据（阶段 10 遗留，真实帖子待测）；待用户提供真实 IG 帖子链接验证 GraphQL/embed 链路
- 本次文档更新时间：10.06 01:45

## 阶段 14 IG 解析打通 + 图集隔离 + 风控定性 [计划时间：09.28 01:20 BY ZCode][完成时间：09.28 14:36 BY ZCode]
- 已完成（提交 7e543f2 / fa76c43 / b47f426 / 0fdbd03）：
  - **Cookie 注入修通**：adb root 写 SP 文件注入 407 字符，登录态生效（WebView title "unavailable"→"Instagram"）；坑：重装后 uid 变化导致 chown 旧 uid 失效（注入 0 字符），需按新 uid（dumpsys package 查）修正
  - **IG 新版数据结构适配**：dump WebView 渲染页（debug_last_page.html）分析发现 `video_url/playable_url/display_url` 已消失 → `video_versions:[{width,height,url}]` / `image_versions2.candidates`（URL `\/`+`\u0025` 双重转义）；extractFromPageHtml 按新键名重写（旧键名兜底），图片跳过视频首帧缩略图
  - **实测通**：reel（视频·原画质 720p）、/p/ 视频帖（原画质+备选 2/3）、图集帖（图 1-N）全部本地解析成功
  - **图集推荐流污染修复**：帖子页内嵌"更多帖子"推荐流（image_versions2 达 35 个），全页扫描把陌生帖视频混进结果（用户复制图集第 3 张却得陌生人视频的根因）→ ownPostScope 按 `xdt_api__v1__media__shortcode__web_info.items[0]` 括号配对截取正帖，提取全部限定该范围
  - **img_index 支持**：链接带 `?img_index=N`（用户复制图集单图）时对应图排到首位，chip 标注「你选的第 N 张」
  - **/p/ 与 /reel/ 渲染行为不同**：WebView fetch 硬编码 /reel/ 使 /p/ 链接白等 40s 超时 → 原路径优先，/p/ 登录态 5.5s 完成（1556KB）
  - **主页误报过滤**：浏览 IG 主页也触发解析（通用解析抓 8 张装饰图）→ 扫描通道校验 pathHints，无作品路径不弹窗
  - **风控定性**：用户报"IG 无法刷新"——逐层排查（Clash 上游 200/模拟器代理栈通/百度正常/Chrome 能收到 IG 页面）→ 真因是 **IG 风控强制页** "Your email address may not be secure"（高频登录态解析+模拟器+数据中心 IP 触发），非网络问题
- 改动文件：`parser/parsers/InstagramParser.kt`（ownPostScope/balancedSlice/新结构提取/img_index）、`parser/parsers/HtmlUtil.kt`（unescapeJsonOf）、`app/clip/WebViewHtmlFetcher.kt`（探针+debug 落盘）、`app/clip/ClipAccessibilityService.kt`（主页过滤）
- 测试结果：`:parser:test` 35 例全绿（--rerun-tasks 强制重跑）；IG 三类链接实测全通；产物待用户实测下载
- 风险：IG 风控持续触发会封号/限流；`video_versions` 结构仍可能再变（探针+dump 手段已固化）
- 下一阶段入口：风控解除后复测刷新与下载；真机回归；`:downloader` 单测；release 签名
- 本次文档更新时间：10.06 01:45

## 阶段 15 气泡状态机与动效 + 单双击手势 + 计数徽标 [计划时间：09.28 15:30 BY ZCode][完成时间：09.28 16:50 BY ZCode]
- 已完成（按 PLAN 阶段 14）：
  - **气泡状态机**：新增 `floatwindow/BubblePhase.kt`（纯 Kotlin 无 Compose 依赖）——7 相位：Idle（蓝呼吸）/ Parsing（黄旋转）/ ParseOk（绿闪 1.2s）/ ParseFail（红闪 1.5s）/ Downloading(percent)（进度环，percent=null 为合并中不定向弧）/ DownloadOk（紫闪 2s）/ DownloadFail（红闪 2s）。服务层 `setPhase(phase, revertMs)` 统一驱动：瞬态相位到点自动回 Idle
  - **挂接点**全部复用现有流程：`showAndParse` 入口（Parsing）/结果分支（ParseOk/ParseFail）、`downloadCurrent`（Downloading(0)）、`progress.collect`（DOWNLOADING→进度、MERGING→null、终态→DownloadOk/DownloadFail/Idle）
  - **卡死防护**：Parsing 是无自动回退的持续相位——弹窗超时收起（hidePopup）与 parse 结果早退分支（弹窗已被接管）两处收口回 Idle；Downloading 相位在弹窗收起后保留（后台下载进度环继续）
  - **BubbleContent 动效重写**：光环全 Canvas 绘制无重布局（Idle 呼吸=InfiniteTransition alpha、旋转弧=sweep 110°/700ms、进度环=360°·pct/100 + alpha0.18 淡色轨道）；描边色 animateColorAsState 0.3s 过渡；呼吸/旋转只在 draw 阶段读状态，无重组开销
  - **hasPending 布尔 → pendingCount 计数**：下载搁置/抑制窗口/autoPopup 关闭时累加，showAndParse 消费与下载终态清零；气泡左上角徽标（>9 显示 9+），旧右上角绿点移除
  - **单击/双击区分**：**方案修正**——PLAN 原定 Compose `pointerInput detectTapGestures`，但气泡拖拽在 View 级 `setOnTouchListener` 实现，Compose 内容一旦加 pointerInput 就会认领触摸事件导致拖拽收不到 DOWN 直接失效 → 改为 View 监听器内计时：双击窗口 250ms，单击延迟到窗口过期执行、双击到来即取消。单击=迷你面板（原行为），双击=跳主界面
  - **调试通道**：`ACTION_DEBUG_PHASE`（`--es phase parsing|parse_ok|parse_fail|downloading|download_ok|download_fail|idle [--ei percent N]`）供 adb 直接驱动状态机做视觉验收，后续阶段复用
- 未完成/遗留：
  - pendingCount 徽标未做运行时触发验证（触发路径=autoPopup 关闭或下载搁置，实现仅 5 行 Compose，构建覆盖，真机回归复验）
  - 60fps 硬指标在 swiftshader 软渲染模拟器上无法真验（50th=29ms 属软渲染瓶颈，绘制负载仅 64dp Canvas，真机回归复验）
- 改动文件：`app/floatwindow/BubblePhase.kt`（新增）、`app/floatwindow/FloatingWindowService.kt`
- 测试结果：`assembleDebug` 全绿；`:parser:test` 全绿（parser 无改动）；模拟器实测：8 相位截图逐个核对全对（含 IG 前台叠加场景）、双击跳主界面 ✓、单击迷你面板 ✓、点空白收回 ✓；`dumpsys gfxinfo` 采样通过
- 风险：真实解析→ParseOk 链路未在模拟器复测（IG 风控未解除，视觉由调试通道覆盖，真实链路随阶段 15/17 复测）；`adb root` 后重装应用 uid 变为 10205（坑 19 的 uid 漂移现象延续）
- 环境教训：**`F:\AndroidDev\build.bat` 在 Git Bash 下必报"命令语法不正确"**（UTF-8 中文注释撞 cmd 代码页）→ Git Bash 下直接 `export JAVA_HOME/GRADLE_USER_HOME/ANDROID_HOME` 后调 `gradle.bat -p . --no-daemon`；**Git Bash 的 MSYS 路径转换会改写 adb shell 里的 `/sdcard/...` 设备路径** → 加 `MSYS_NO_PATHCONV=1`，且 adb pull 本地目标用相对路径
- 下一阶段入口：**阶段 15 自动解析→自动下载流水线**（守卫从单值改集合、autoDownload 开关、IG 风控保险丝=仅 WiFi+失败退避）；阶段 17 reels 403 可与之并行；随后 16（并发解析）、18（图集多选）
- 本次文档更新时间：10.06 01:45

## 阶段 16 自动解析→自动下载流水线 + 下载详情卡 + 修复 8（query 截断）+ 单击识别交互 [计划时间：09.28 16:55 BY ZCode][完成时间：09.28 19:22 BY ZCode]
- 已完成（按 PLAN 阶段 15）：
  - **自动流水线**：`autoRecognize` 接管 observeLinks 的自动路径——后台解析（气泡黄圈）→ 解析成功按资源形态分流：单资源/全视频变体组 → **自动 enqueue 下载无 UI 确认**（绿闪 600ms→进度环→紫闪）；图集/多图/混合形态或自动下载不可用 → 回退现有 Ready 选择卡；解析失败 → 红闪+Failed 卡。`autoDownloadCandidate` 判定规则：media.size==1 直取；多条但 kind 全为 VIDEO（同源清晰度变体，如 reel 原画质+备选、X 多码率）取推荐首位（与选择卡默认选中一致）；其余（图集）返回 null
  - **守卫重构**：`downloadActive` 单值删除；`suppressedUrl` 单值 → `suppressedUrls` Map（带 SUPPRESS_MS 过期清理，markSuppressed/isSuppressed）；新增 `pipelineUrls` 防同 URL 在 seen_links 异步落库前重复触发解析；新增 `popupFreeForAuto()`——自动路径只在弹窗空闲（Hidden/Mini）时占用弹窗，用户正在看的弹窗不抢；手动路径（迷你面板识别/重试/SHOW_LAST）保留原 showAndParse 全交互
  - **下载详情卡**：`PopupUiState.Downloads` + `DownloadRow`（taskId/title/platformName/percent/sizeText）；下载中单击气泡展示（autoTasks 非空分流），进度事件实时刷新（refreshDownloadsCard），任务清空自动收起；`autoTasks: LinkedHashMap<taskId, DownloadRow>` 主线程读写
  - **IG 风控保险丝**：autoDownload 开关（DataStore 默认开+设置页 UI）；WiFi 保险丝复用 `wifiOnly` 设置（`isOnUnmetered` 判 WiFi/以太网，仅 WiFi 开启且在计量网络时不自动下载，回退弹卡）；**失败退避熔断**：自动下载连续失败 ≥3 次暂停 10 分钟（`autoDownloadSuspendedUntil`），成功即复位
  - **修复 8（真 bug，阶段 15 验收中发现）**：`UrlUtil` 的 URL_PATTERN/BARE_HOST_PATTERN 字符类把半角 `?` 列入排除集——**所有带 query 的链接从文本召回时 query 被整段截断**（YouTube `?v=` 丢 ID 必挂、小红书 `xsec_token` 丢失、IG `img_index` 丢失）；此前 X/IG 实测链接恰好无 query 未暴露。修复=排除集保留全角 `？` 移除半角 `?`；新增 3 个回归用例（YouTube v 参数/XHS token/全角问号截断）
  - 调试通道沿用 ACTION_DEBUG_PHASE；youtube 视频的 ID 提取、失败降级文案（"需要远端解析服务"）实测正确
- 未完成/遗留：
  - **详情卡运行时验证**：模拟器代理带宽高（10.2MB 视频 ~3s 下完），无法自然触达"下载中点击气泡"窗口；行为已反向验证（下载终态后单击正确回退迷你面板=autoTasks 清空正确），详情卡点击分流真机回归复验（真机网络慢窗口长）
  - **保险丝运行时验证**：模拟器网络=以太网视为不限量，wifiOnly/退避熔断未自然触发，代码+构建覆盖，真机回归
  - IG reel 自动下载全链路未测（IG 风控未解除）；**YouTube 链路 2026-09-28 当天 Piped 全实例不可用**（kavin=526/adminforge=301/reallyaweso=502/private.coffee=500），外部服务波动非代码问题，URL 修复的正确性已由失败文案变化实证（"无法提取 ID"→"需要远端解析服务"）
- 改动文件：`floatwindow/FloatingWindowService.kt`（自动流水线核心）、`floatwindow/BubblePhase.kt`（无改动）、`floatwindow/PopupUiState.kt`（+Downloads/DownloadRow）、`floatwindow/ClipPopupContent.kt`（DownloadsBody）、`data/SettingsRepository.kt`（autoDownload）、`ui/settings/SettingsScreen.kt`（开关 UI）、`parser/core/UrlUtil.kt`（修复 8）、`parser/src/test/.../UrlUtilTest.kt`（+3 用例）
- 测试结果：`:parser:test` **38 例全绿**（35+3）；`assembleDebug` 全绿；模拟器实测：**X 图片推文全自动落盘 `Pictures/ClipDown/`（694KB jpg，气泡黄→蓝→紫无点击）**、**X 视频推文全自动落盘 `Movies/ClipDown/`（10.2MB mp4）**、已见链接重发无任何反应（气泡 Idle 前后对比）、关 autoDownload 开关→分享新链接→Ready 选择卡弹出（连拍 24s：黄→弹卡→15s 收起）
- 风险：Piped 公共实例持续不稳定（远端解析兜底的设置项价值上升，用户自建 cobalt 是正解）；自动路径弹卡被用户占用时静默放弃（无角标提示，阶段 16 徽标计数可补）；多任务并发下载时进度环显示最新事件（阶段 16 排队后自然消解）
- 测试方法教训：**adb `am start SEND` 在应用 task 处于前台时会被 delivered-to-top 投给 MainActivity，ShareTargetActivity.onCreate 不执行**（logcat 证据：result code=3 + onActivityRestartAttempt MainActivity）——分享通道测试必须先 force-stop 或 HOME 切后台；真实用户路径（其他 App 里点分享）无此问题
- 用户反馈迭代（2026-09-28 阶段 15 后追加）：**单击气泡 = 直接识别剪贴板**。原"复制后自动触发"依赖无障碍通道，模拟器上无障碍反复失效（坑 14 假死 + install -r 清授权）导致用户侧"复制无反应"；改为用户主动点击：单击气泡 → 借道 ClipGateActivity 读剪贴板（悬浮窗+前台豁免，**不依赖无障碍**）→ `autoRecognize(force=true)` 直接走流水线，反馈全在气泡相位（黄圈解析→进度环/红闪），不再开迷你面板；剪贴板无链接/读取失败才弹 Mini 提示卡；下载中单击=详情卡、双击=主界面不变；force 绕过识别记忆与抑制窗口（用户点气泡=明确意图，旧链接也能重新下载），pipelineUrls 保留防重复点击。模拟器验证：tap 气泡→gate 读取→黄圈解析→收口回 Idle 全链路截图实证；同日用户实测 IG 图两张自动落盘（11:09/11:10）
- 下一阶段入口：**阶段 16 并发解析队列**（依赖的守卫重构已就位）或**阶段 17 reels 403**（X 视频变体组自动下载已验证，IG 侧待风控解除）；建议 16 先行
- 本次文档更新时间：10.06 01:45

## 阶段 17 并发解析可见性三徽标 + 瞬态相位回退修正 [计划时间：09.28 19:30 BY ZCode][完成时间：09.28 20:39 BY ZCode]
- 已完成（按 PLAN 阶段 16；并发骨架已由阶段 15 承载——autoParseJobs/pipelineUrls 每条链接独立 Job 互不取消，下载侧引擎自带 maxConcurrent 排队，本阶段补齐可见性与一致性）：
  - **三徽标**：左上绿=搁置待处理（pendingCount 原语义）、左下黄=**解析中 N**、右下蓝=**下载中 M**；parsingCount/downloadingCount 两个 mutableIntStateOf，在 autoRecognize 入队/出队、startAutoDownload enqueue、watchAutoTask 终态、downloadCurrent（手动下载同样计数）各转换点维护；>0 才显示，9+ 封顶；抽出 CountBadge 复用（align 由 BoxScope 调用方传入）
  - **相位冲突修正**：并发解析下瞬态闪现（ParseOk/ParseFail/DownloadOk）到点回退时，若 parsingCount>0 则回 Parsing 而非 Idle——多链接同时解析时黄圈不会被单条结果"提前熄灭"
  - **pendingCount 消费 bug**：force 点气泡识别现在也清零 pendingCount（原只在 showAndParse/下载终态清零，新交互下 deferred 计数会永久残留）
  - LinkCenter 未改动：15s 去重 + 每条独立事件已满足并发需求，PLAN 提的"detected 携带序号"无实际用途（YAGNI）
- 未完成/遗留：
  - **双链接并发峰值（徽标=2）未在模拟器抓到**：第一条 SEND 被 delivered-to-top 吞（坑 27 注入限制），且 Piped 全挂只有单条入队——并发正确性由独立 launch 结构保证，用户真实"5 秒连发多条"场景待实测；同平台并发节流（IG 风控放大风险）未做，PLAN 风险项保留
- 改动文件：`floatwindow/FloatingWindowService.kt`（计数状态+三徽标+setPhase 回退修正+pendingCount bug）
- 测试结果：`assembleDebug` + `:parser:test` 38 例全绿；模拟器连拍验证：黄色旋转光弧+黄徽标"1"同框（解析中）→ Piped 失败卡 → 收口，徽标与相位流转全程一致
- 风险：同平台并发解析对 IG 的风控放大（未节流）；多任务并发下载时进度环显示最新事件任务（不聚合，阶段 16 PLAN 未要求，记录现状）
- 下一阶段入口：**阶段 18 图集全选/多选下载 UI**（纯 UI 不依赖外部服务健康，批量 enqueue=DownloadController.enqueueAll 已有）或**阶段 17 reels 403**（IG 侧待风控解除，X 变体组自动下载已验证）
- 本次文档更新时间：10.06 01:45

## 阶段 18 reels 403 修复（双重转义根因，含修复 9） [计划时间：09.28 19:40 BY ZCode][完成时间：09.28 21:13 BY ZCode]
- 已完成（按 PLAN 阶段 17 排查序）：
  - **排查序①（请求头）**：代码审查全链路——解析器 `Referer: instagram.com`（InstagramParser igHeaders）→ TaskEntity.headers 原样保存 → DownloadEngine 按任务传 HttpFileDownloader → OkHttp header()，**无丢失**；请求无 User-Agent（OkHttp 默认）但实测证明不敏感
  - **根因（修复 9）**：下载任务库里 FAILED 任务的 URL 是 `https:\\/\\/scontent...`——**JSON-in-JS 双重转义未还原**。IG 页面数据里 `/` 写作 `\\/`、`%` 写作 `\\u0025`，阶段 13 重写的 video_versions HTML 正则提取只做单层反转义（unescapeJson 只处理 `\/`），URL 带字面反斜杠进下载器 → CDN 403。图片走 kotlinx 标准解码路径不受影响——完美解释"识别正确、图片能下、视频必挂"
  - **铁证链**：任务库 FAILED URL 带字面 `\\/` → 宿主机手动还原同一 URL → curl **裸请求 200**（UA/Referer/Cookie 均不需要）→ `oe=` 签名在有效期内（排除时效）→ 修复=纯转义问题
  - **修复**：`HtmlUtil.unescapeJson` 前置 JS 层解码（`\\`→`\`，把双重形态降为单层）再做常规 JSON 解码（\uXXXX、\/）；清理两条被 \uXXXX 正则覆盖的死代码（\u002F、\u0026 显式 replace）；新增双重/单层转义回归用例
  - **诊断能力**：DownloadEngine 终态失败此前 logcat 完全静默（本次排查最大障碍）——失败时打 `Log.w("DownloadEngine", 任务名+原因+完整 URL)`
- 排除项（实测证据）：请求头无问题（curl 200 无需任何头）；URL 时效不是问题（签名有效期内、流水线秒级下载）；Cookie 不需要；blob 误判不存在（video_versions 是直链）
- 改动文件：`parser/parsers/HtmlUtil.kt`（unescapeJson 双层解码）、`parser/src/test/.../InstagramParserTest.kt`（+1 回归用例，39 例全绿）、`downloader/engine/DownloadEngine.kt`（终态失败日志）
- 测试结果：构建+`:parser:test` 全绿；**用户实测 IG reel 下载成功**（此前同一条 403）；IG 图片帖实战全通（3 张自动落盘，含流水线自动触发）；X 视频变体组自动下载此前已验证
- 风险：IG 页面转义形态再变时 video_versions 可能再坏（探针+dump 手段已固化，任务库 URL 是现成取证位）；`unescapeJson` 对"内容里合法字面 `\\`"的错解风险仅限非 JSON 上下文（当前调用方全是 JSON 切片，安全）
- 下一阶段入口：**阶段 18 图集全选/多选下载 UI**（最后一个 PLAN 阶段；批量 enqueue=DownloadController.enqueueAll 已存在）
- 本次文档更新时间：10.06 01:45

## 阶段 19 图集全选/多选下载 UI [计划时间：09.28 21:00 BY ZCode][完成时间：09.28 21:18 BY ZCode]
- 已完成（按 PLAN 阶段 18，PLAN 阶段 14-18 至此全部实现）：
  - **判定下沉 parser 模型**：`ParseResult.isAlbumMultiSelect`（多项且非"全视频变体组"）——弹窗/主界面/自动流水线三处共用同一语义；`autoDownloadCandidate` 简化为 `if (isAlbumMultiSelect) null else firstOrNull()`
  - **Ready 多选态**：`selectedIndex: Int` → `selectedIndices: Set<Int>` + `multiSelect: Boolean`（旧 `selected` 兼容属性保留，取首个选中）
  - **弹窗选择卡**：multiSelect 时 chips 点击 toggle；快捷操作行"全选 / 仅视频（取消非视频选中）/ 清空"；按钮文案"下载所选 N 项"
  - **批量下载**：multiSelect 路径走 `DownloadController.enqueueAll` 批量入队 → downloadingCount 批量累加 → markSuppressed → 弹窗收起（进度由气泡徽标与详情卡承接）；单选（视频变体组）保留原弹窗内进度闭环
  - **主界面同步**：HomeScreen ResultCard 同样多选化（同判定/同快捷操作/批量 enqueueAll）；顺手修复"新解析结果不重置选中"的旧问题（两处 result 赋值点均重置 setOf(0)）
- 测试结果：`assembleDebug` + `:parser:test` 39 例全绿；已装机
- 待实测（用户配合）：图集帖点气泡 → 多选卡出现 → 全选 N 张 → N 任务全部完成；多选 3 张 → 仅 3 任务（PLAN 验收标准）
- 风险："仅视频/全选/清空"基于闭包旧快照逐项 toggle（依赖 service onSelect 同步 copy，已按此实现）；IG 图集帖本身受风控波动影响
- 下一阶段入口：**PLAN 阶段 14-18 全部完成** → 回归主线下一步：真机回归（累积项见 HANDOFF）→ release 签名/R8 → `:downloader` 单测
- 本次文档更新时间：10.06 01:45

## 阶段 20 下载页图集分组（sourceUrl/DB v2）+ 条形选择卡独立小窗 [计划时间：09.28 21:30 BY ZCode][完成时间：09.28 21:51 BY ZCode]
- 用户需求：①下载记录可点链接/图集整包呈现/同源分组去冗余；②图集弹窗改为气泡上方拉出的小长条（缩略图+全选/部分选择+下载）
- 阶段 19 已完成（提交 13a92bc）：
  - **数据模型**：TaskEntity + `sourceUrl`（来源帖子链接）；DB v1→v2 迁移（ALTER TABLE ADD COLUMN source_url，onUpgrade 分支）；toCv/toTask 同步
  - **链路**：DownloadController.enqueue/enqueueAll + sourceUrl 参数；四个调用点全部传入（自动流水线/弹窗单选/弹窗批量/主界面），来源统一取 `ParseResult.sourceUrl`
  - **下载页重写**：按 sourceUrl 分组——同源聚合成"整包"组卡（标题+图集 N 张+完成/失败/下载中聚合态+聚合进度条），点组头打开第一张完成图；"明细"展开逐张紧凑行（可暂停/继续/删除/打开）；每条含"打开"（MediaStore URI）与"来源"（帖子链接）双入口；无 sourceUrl 的旧记录维持平铺（历史数据无分组键，属预期）
- 阶段 20 已完成（提交 d8bcdbc）：
  - **条形选择卡**：图集（Ready.multiSelect）不再弹全屏居中大卡，改为**气泡上方独立小窗**（WRAP_CONTENT、320dp 宽、定位=气泡 y-96dp、x 向左让位并钳制屏内、FLAG_NOT_TOUCH_MODAL 窗外点击穿透不抢操作）；收起态=首图缩略图+"图集 N 张/已选 M"+全选 Checkbox+下载+展开箭头+关闭；**默认全选**
  - **部分选择**：取消全选勾 → 条形向下延伸展开 3 列缩略图网格（heightIn 300dp 内滚动），点图 toggle（选中蓝描边+角标勾）；重新勾选=全选并收起；展开箭头独立控制
  - **popupHost 双形态**：条形/居中卡窗口参数不同，ensurePopupHost 按当前 state 形态签名重建（居中卡保留 blur behind，条形不用）；气泡拖拽后条形定位跟随（bubblePos 字段）
  - ReadyBody（居中卡）仅承载视频变体组单选；条形内的选择/下载复用既有 onSelect(toggle)/onDownload(批量 enqueueAll) 回调
- 测试结果：`assembleDebug` + `:parser:test` 39 例全绿；已装机；下载页新 UI 渲染验证通过（旧无 sourceUrl 数据平铺显示正常）
- 待实测（用户配合）：图集帖点气泡 → 气泡上方条形卡 → 取消全选展开网格挑图 → 下载；下载页新图集记录自动归组
- 风险：条形展开超高图集（>7 行）在 300dp 内滚动（窗口不无限长）；AsyncImage 加载原图做缩略图（coil 下采样，弱网首帧稍慢）；NOT_TOUCH_MODAL 下条形仅 15s 自动收起（dismiss timer 沿用）
- 下一阶段入口：真机回归（累积项）→ release 签名 → `:downloader` 单测
- 本次文档更新时间：10.06 01:45

## 阶段 21 竖向窄条返工：Mini/图集共用 + toggle 开收 + 拖拽跟随 [计划时间：09.29 01:30 BY ZCode][完成时间：09.29 02:14 BY ZCode]
- 用户反馈（附截图）：①条形应为**竖向**而非横向；②窄条**跟随气泡移动**、点气泡拉开/再点收起（组件语义）；③**Mini 默认弹窗同样竖条化**，去掉全屏模糊+居中大卡
- 已完成（提交 6f88d9f）：
  - **竖向窄条**：128dp 宽竖排容器（SideBarContainer），Mini（logo/剪存/hint/识别按钮）与图集（首图缩略图+计数+全选行+下载+挑图/收起+2 列缩略图网格）两种内容共用；去掉全屏 scrim 与系统模糊——窗外内容清晰可见
  - **toggle 语义**：onBubbleClick 改为"已有弹层→收回；下载中→详情竖条；空闲→识别"。点气泡开、再点气泡收
  - **跟随拖拽**：sideBarWinParams 引用 + followBubble()（与初始定位同一相对公式，绝对对齐无漂移），气泡 ACTION_MOVE 时同步移动条形窗口
  - 窗口定位：气泡**左侧**（x=气泡左缘-条宽-8dp，钳制屏内），顶缘对齐气泡顶-20dp；展开网格向下延伸不遮挡气泡；Mini 与图集切换形态时窗口重建
  - 清理：旧 MiniBody（全屏居中版）删除，material Checkbox 改为紧凑自定义勾选框（竖条空间）
- 测试结果：构建+39 例全绿；模拟器实测：点气泡→Mini 竖条弹出（贴气泡左侧、无遮罩）✓、再点气泡→收回 ✓（截图核对）；图集竖条与拖拽跟随待用户实测
- 下一阶段入口：真机回归（累积项）→ release 签名 → `:downloader` 单测
- 本次文档更新时间：10.06 01:45

## 阶段 22 IG 污染修复（修复 10：code 切段/入口清洗/红闪延长）+ 竖条重排（气泡正下方 64dp） [计划时间：09.29 02:30 BY ZCode][完成时间：09.29 03:16 BY ZCode]
- 用户实测反馈四问题：识别到别的视频、403 复现、失败/进度/完成的气泡反馈没看到、竖条应竖向且贴气泡下方同宽
- **修复 10（识别污染根因，最高优先）**：任务库+logcat 取证——失败的"图片"实为**别的 reel 的视频封面帧**（efg 解码 `CLIPS.xpids.720.video_default_cover_frame`）、失败的 mp4 是另一个 4 秒视频；dump 实测**IG items[0] 结构已变**：容器内嵌 3 个 code（2 个推荐流空壳块 video_versions=null + 正帖在最后），阶段 13 的"items[0] 只含正帖"假设失效，全段扫描把嵌块媒体混入
  - **修复**：新增 `ownCodeSegment`——items[0] 内按 `"code":"X"` 切段，只保留正帖 shortcode 所在段（dump 离线验证：正帖段 13KB 只含 1 视频+1 图，推荐流全隔离；对嵌套形态免疫）
  - **取证能力**：autoRecognize 成功分支打解析结果日志（resolverId+media 数量+每条 URL 前 70 字符）——污染再现直接看 logcat
- **403 复现定性**：失败 URL 全部 `\/` 转义残留——但**全部产生于修复 9 装机前的旧进程**（pid/时间链闭合）；修复 9+新装的**入口清洗**（DownloadController.enqueue 对 item.url 强制还原 `\/`）双保险，入库 URL 必净；下载页看到的 403 记录=历史遗留
- **反馈增强**：ParseFail 红闪 1.5s→4s、DownloadFail 2s→6s（刷视频时错过 2s 红闪是"没看到"的主因）
- **竖条重排**：改到**气泡正下方**（y=气泡顶+72dp），**宽度与气泡一致（64dp）**，圆角 18dp 匹配；Mini（logo+短提示+识别图标钮）与图集（首图+计数+全选勾+下载图标钮+挑图）内容适配 64dp；展开网格改单列向下延伸；拖拽跟随保持
- 测试结果：`:parser:test` **40 例全绿**（39+1 嵌套推荐块回归用例，夹具按真实 items[0] 嵌套结构构造）；构建全绿；已装机
- 改动文件：`parser/parsers/InstagramParser.kt`（ownCodeSegment+segment 提取）、`parser/src/test/.../InstagramParserTest.kt`、`downloader/DownloadController.kt`（入口清洗）、`app/floatwindow/FloatingWindowService.kt`（日志/红闪/竖条参数）、`app/floatwindow/ClipPopupContent.kt`（64dp 适配）
- 下一阶段入口：用户复测（识别正确性/下载成功反馈/竖条形态）→ 真机回归 → release
- 本次文档更新时间：10.06 01:45

## 阶段 23 embed 通道 code 切段隔离 + 全通道解析日志（修复 10b） [计划时间：09.29 18:00 BY ZCode][完成时间：09.29 18:20 BY ZCode]
- 用户复测反馈"还是识别到别的视频"（修复 10 之后）——模拟器随后被关闭无法在线取证
- **代码层排查**：修复 10 只加固了 WebView 通道（extractFromPageHtml）；解析优先级为 登录态 GraphQL（单帖节点无污染）→ **embed（全页 jsonField 扫描，embed 页含 Related reels 推荐区）** → WebView → oEmbed——**embed 是最后一条未加固路径**（GraphQL 被 IG 风控拒绝时落到它）
- **修复 10b**：parseEmbed 复用 ownCodeSegment 按 code 切段（embed 数据无 code 字段时保持原样兼容）；video_url/display_url 提取限定正帖段，og meta/EmbeddedMediaImage 保持全页
- **全通道成功日志**：private/embed 成功时打 media 清单（ctx.log → ParserEngine tag），加上 -page 通道与服务层日志——污染再现时 logcat 一步定位是哪条通道混入
- 测试：构建+40 例全绿；**模拟器离线，装机与复测待用户开启模拟器后进行**
- 复测核对点：复现时 `adb logcat -d -s ParserEngine FloatingWindowService DownloadEngine` 三组日志可完整还原通道与媒体来源
- 下一阶段入口：装机+复测 → 若仍污染按日志定位（已无盲区）→ 真机回归
- 本次文档更新时间：10.06 01:45

## 阶段 24 embed 单视频帖媒体净化 + 出口统一清洗去重（修复 10c） [计划时间：09.29 18:50 BY ZCode][完成时间：09.29 19:01 BY ZCode]
- 用户复测日志（"解析成功 ig-local-v1-embed media=3"）揭示真正的"识别到别的视频"形态：**embed 通道把单视频帖解析成 1 视频 + 2 张封面帧图**（同一封面 URL 的两种提取形态：EmbeddedMediaImage img 标签 + display_url jsonField，转义形态不同未被去重）→ media=3 且混合形态 → `isAlbumMultiSelect=true` → 弹图集竖条全选 → 用户下载到"视频+2 张封面图"，观感即"串到别的视频"（封面图打开是图片）。**识别本身没串**（视频 URL 正确）
- **修复 10c**：
  - **embed 语义修正**：`video_url` 存在时（单视频帖）媒体只保留视频——封面帧图全部丢弃，media=1 → `isAlbumMultiSelect=false` → 走自动下载单视频闭环（图集帖无 video_url 不受影响）
  - **出口统一清洗**：三条通道（private/page/embed）返回前统一 `sanitized()`——URL 还原 `\/` 转义（无论几层残留）+ 按 URL 去重（同图多形态提取的重复项合并）；与 DownloadController 入口清洗构成双保险
  - 日志还确认 embed 通道确实在用（GraphQL 被拒后落到 embed）——10b 的 embed code 段隔离有效
- 测试：40 例全绿（"无 Cookie 走 embed"用例更新为断言视频帖不带封面图媒体）；已装机，日志已清
- 下一阶段入口：用户复测 reel（预期：竖条不再出现，黄→蓝→紫单视频自动落盘）→ 图集帖复测竖条 → 真机回归
- 本次文档更新时间：10.06 01:45

## 阶段 25 WebView 图集 DOM img 兜底提取（修复 10d）+ 通道选择日志 [计划时间：09.29 19:10 BY ZCode][完成时间：09.29 19:39 BY ZCode]
- 用户复测：图集帖"变成自动下载没有跳出竖条"（视频帖修复确认 ✓）
- 取证链：解析日志 `ig-local-v1-page media=1` → dump 分析（该图集帖 items[0] `carousel_media=null`、`media_type=1`、仅 1 个 image_versions2 块=顶层首图）→ **IG 给 WebView 渲染页的图集数据残缺，子图不进 JSON** → media=1 被判单资源自动下载首图
- **子图实际在 DOM**：dump3 img 特征分析——头像（t51.2885-19 / s150x150）×4 + 内容图（t51.82787-15 + stp=dst-jpg_e35_tt6）×6
- **修复**：extractFromPageHtml 图片提取后，当 `images.size<=1 && videos.isEmpty()`（疑似被简化的图集）时扫 DOM `<img>` 兜底——排除头像路径/小尺寸与已提取项，剩余内容图追加进候选；混入风险由竖条的用户挑选兜住（不再自动误下）
- 附带诊断：GraphQL（唯一含完整图集数据的通道）本次返回 HTML 页（"GraphQL 返回异常格式"，会话-IP 绑定风控，阶段 6 定性的老问题在代理环境无 App 层解）；embed 通道已废（页面无媒体数据）；通道选择日志（cookie 状态+code，提交 f926c72）
- 测试：构建+40 例全绿；已装机+日志清空
- 改动文件：`parser/parsers/InstagramParser.kt`（DOM 兜底）、`parser/test/.../InstagramParserTest.kt`（无新增，既有用例回归）
- 风险：DOM 兜底可能混入页面推荐流内容图（由竖条挑选兜住）；GraphQL 风控为环境级问题（真机+家宽大概率消失）
- 下一阶段入口：用户复测图集竖条（预期全子图出现在竖条）→ 真机回归 → release
- 本次文档更新时间：10.06 01:45

## 阶段 26 :downloader 单测 30 例（TestHttpServer，无新增依赖）+ PKCS5 降级修复 [计划时间：10.01 00:30 BY ZCode][完成时间：10.01 01:06 BY ZCode]
- 背景：主线回归项里唯一不依赖真机/模拟器、可自主收口的一项（HANDOFF 下一步 ④）
- **测试基建**：`downloader/build.gradle.kts` 新增 `testOptions { unitTests.isReturnDefaultValues = true }`（触达 `android.media` 的路径走 stub 默认值）+ `testImplementation("junit:junit:4.13.2")`；**未引入新依赖**——HTTP 服务端用 `TestHttpServer.kt`（纯 JDK `ServerSocket`，支持 Range 206/全量 200/任意状态码/HEAD/分块慢速响应 + 记录请求头供断言，可替代 mockwebserver，避免联网拉包）
- **覆盖**（5 个测试类 / 30 例）：
  - `HttpFileDownloaderTest`（7）：全量落盘、**续传 Range 头断言 `bytes=N-` 且结果完整**、服务端不支持 Range 时**从头覆盖**（脏数据不残留）、404 → `DownloadHttpException(code)`、取消 → `DownloadCanceledException` 且文件未写满、`probe()` 解析 Content-Length/Content-Type/Accept-Ranges、probe 错误码
  - `M3u8DownloaderTest`（9）：`sequenceIv` 大端、`hexToBytes`（0x 前缀/短输入补零）、**AES-128 解密（序号 IV / 显式 IV）**、key 取不到降级直通、媒体列表分片按序拼接、主列表**选最高带宽档**、分片失败抛 `DownloadHttpException`
  - `MediaRemuxerTest`（4）：`concatSegments` 顺序拼接/空列表失败、`concatInitAndMedia` init 在前/输入缺失失败
  - `TaskModelsTest`（6）：终态/活跃态判定、progress（完成=1、未知总量=0、>1 钳制）、DownloadConfig 默认值
  - `DownloadControllerTest`（4）：**修复 9 第二道防线 `sanitizeTaskUrl` 回归**（`\/` 单层/双层均清）、`guessExt`（忽略 query/回落 mp4）
- **生产代码改动（3 处，均为可视化/可测化，无行为变更）**：`M3u8Downloader` 的 `decrypt/sequenceIv/hexToBytes` private→internal；`DownloadController.guessExt` private→internal 并抽出 `internal fun sanitizeTaskUrl`（原内联 `replace("\\/","/")`）
- **顺带修复（真问题）**：`M3u8Downloader.decrypt` 原先硬编码 `AES/CBC/PKCS7Padding`——SunJCE（桌面 JVM）未注册该变换，`Cipher.getInstance` 抛异常后被 `runCatching` 静默吞掉，**解密静默失效、直接落密文**（Android 上正常，跨运行时必踩）。改为 PKCS7 优先、PKCS5 降级（对 16 字节块 AES 完全等价，Android 行为不变）
- 定性记录：HLS 分片失败**不做静默降级**，异常上抛由 `DownloadEngine.runCatching` + 退避重试统一兜（与返回 false 殊途同归），测试按真实契约断言
- 未覆盖（需 Robolectric/仪器化，暂不做）：`TaskDatabase`（SQLiteOpenHelper）、`MediaStoreWriter`、`Notifier`、`DownloadEngine` 并发调度、`remuxToMp4/mergeTracks`（MediaExtractor/MediaMuxer）
- 测试结果：`assembleDebug` + `:parser:test`（40 例）+ `:downloader:testDebugUnitTest`（30 例）全绿
- 下一阶段入口：用户复测图集竖条 → 真机回归（累积项）→ release 签名 + R8
- 本次文档更新时间：10.06 01:45

## 阶段 27 release 签名 + R8（体积 18.6MB→1.77MB） [计划时间：10.01 22:00 BY ZCode][完成时间：10.01 23:22 BY ZCode]
- **签名**：keystore `app/signing/clipdown.jks`（RSA 2048，validity 10000 天，`*.jks` 已 ignore 不入 git）；路径与密码在 `signing.properties`（已 ignore）；`app/build.gradle.kts` 顶部 Properties 加载，文件存在时 release 挂正式签名，否则落回 debug 签名保证可构建
- **R8**：`isMinifyEnabled=true + isShrinkResources=true`；proguard 增补 `ViewTreeLifecycleOwner`/`ViewTreeSavedStateRegistryOwner` keep（bindOwners 反射目标）；parser model 整体 keep 既有；**诊断日志保留**（不做 assumenosideeffects 剥离——个人项目诊断优先）
- **体积：debug 18.6MB → release 1.77MB**；`apksigner verify` 证书通过（CN=ClipDown, O=Gorilla-Kevv）
- **待办：release 装机冒烟**（同包名覆盖 debug——待用户图集复测完成后进行；冒烟清单：气泡显示/点气泡识别/单视频自动下载/图集竖条/下载页分组/设置与 SP cookie 持久化）
- 改动文件：`app/build.gradle.kts`（signingConfig+minify）、`app/proguard-rules.pro`、`.gitignore`（signing.properties）
- **风险/注意**：keystore 丢失则无法为同包名升级签名——**务必备份 `app/signing/clipdown.jks` 与密码（clipdown2026）**；R8 运行时行为待冒烟（序列化/反射点已有 keep，风险低）
- 与 downloader 单测条目（下条）同属阶段 21，双会话并行完成
- 本次文档更新时间：10.06 01:45

## 阶段 28 IG 解析冷却保险丝 + generic 兜底排除 + 下载时间显示（修复 11） [计划时间：10.01 23:40 BY ZCode][完成时间：10.02 00:53 BY ZCode]
- 用户反馈：账号被触发风控面临封号；解析 105 秒过长；解析出 IG 默认图标图；下载页需显示下载时间
- **取证**：日志还原完整链条——IG 风控升级到"无法获取帖子页面"（private 第一步 GET 即拒）→ 四通道全败（105 秒慢失败）→ **GenericParser 兜底抓回 8 个 IG UI 图标**（`static.cdninstagram.com/rsrc.php/*.webp`，即"默认图片"）→ 用户重试 → 1 分钟内同一链接解析 3 次 → 请求风暴加重风控
- **修复 11（三项）**：
  - **解析冷却保险丝**：InstagramParser 连续失败 ≥2 次 → 冷却 10 分钟（期内 parse 直接抛"冷却中"，**零网络请求**）；成功清零。与下载退避同构，保护账号
  - **generic 兜底排除专属平台**：ParserEngine 降级链 step4 仅对 `platform == GENERIC` 生效——专属解析器失败不再产出 UI 图标垃圾
  - **下载页时间显示**：单任务与图集组卡均追加 `MM-dd HH:mm`（updated_at）
- 测试：parser 40 + downloader 30 例全绿；已装机
- 改动文件：`parser/parsers/InstagramParser.kt`（冷却）、`parser/core/ParserEngine.kt`（generic 条件）、`app/ui/downloads/DownloadsScreen.kt`（时间）
- **测试策略（账号风控期）**：①暂停 IG 自动化实测（agent 不再触发识别）②用户验证间隔 ≥10 分钟（冷却期外）③失败后**不要反复重试**（冷却会自动拦截）④优先用 X/YouTube 链接验证非 IG 功能 ⑤IG App 若刷新异常先完成平台验证流程再测
- 下一阶段入口：风控期过后的图集竖条复测 → release 装机冒烟 → 真机回归
- 本次文档更新时间：10.06 01:45

## 阶段 29 气泡+竖条同窗口一体化重构（修复 12，含竖条防重叠 BOTTOM 锚方案） [计划时间：10.02 01:20 BY ZCode][完成时间：10.03 00:47 BY ZCode]
- 用户反馈：竖条与气泡仍重叠（双窗口方案定位微调无法根治）→ 指定新形态：**点击气泡后竖条从气泡边缘"生长"出来，同一整体，再点气泡收起**
- **架构重构（单窗口）**：删旧双窗口（气泡窗+sideBar 窗），改为 `BubbleWindowContent`——气泡圆 + 竖条在**同一 Compose Column** 内纵向连接，竖条开合只改窗口高度，**物理上不可能重叠**
  - 新文件 `floatwindow/BubbleBar.kt`：BarBody（Mini 提示/图集选择两形态，64dp 宽与气泡一致、18dp 圆角匹配）
  - `FloatingWindowService`：bubbleParams 持久引用 + `syncBubbleWindow(state)`——竖条开/关/内容变化时（LaunchedEffect 驱动）更新窗口锚点：下方空间够=TOP 锚竖条向下生长；不够=BOTTOM 锚向上生长；收起恢复 TOP 原位；x 钳制屏内（气泡拖到右缘时竖条左移）
  - **触摸分区**：View onTouchListener 的 DOWN 按 y 判断——气泡圆区(64dp)=拖拽/单击/双击；竖条区 return false 透传给 Compose 控件（挑图/下载按钮可点）
  - ClipPopupContent 收缩为纯居中卡（Loading/单选Ready/Failed/Downloads）；Mini/图集 Ready 的 3 处 `ensurePopupHost()` 误调删除（曾渲染空壳卡+全屏遮罩）
- 测试：70 例全绿（parser 40 + downloader 30）；装机自测：Mini 竖条在气泡下方同宽渲染 ✓（v2 截图验证，v3 x 钳制构建通过已装机未复验）
- **待用户复测**：①X 图集竖条挑图+下载 ②气泡拖到屏幕下半部→竖条改向上生长 ③再点气泡收起 ④IG 冷却期点气泡→失败卡文案"冷却中"
- 已知遗留：BubbleBar 内图集缩略图加载原图 URL（coil 下采样）；竖条内容超 330dp 上限的极端图集未实测
- 本次文档更新时间：10.06 01:45

## 阶段 30 release 装机冒烟（新坑 14：adb shell cat 二进制 CRLF 翻译） [计划时间：10.06 01:50 BY ZCode][完成时间：10.06 11:30 BY ZCode]
- 用户要求"检查后再进行下一任务"→ 启动 `clip34` 模拟器（无窗口 swiftshader），执行阶段 27 待办的 release 装机冒烟
- **环境准备**：
  - `assembleRelease` + `:parser:test` + `:downloader:testDebugUnitTest` 全绿（6m10s）；产物 `app-release.apk` 1.69MB（1771116 bytes），签名 `CN=ClipDown, O=Gorilla-Kevv` ✓
  - 模拟器 `adb root` → 卸载 debug → `adb install app-release.apk` Success → 新 uid=10206
  - 授权：appops SYSTEM_ALERT_WINDOW allow、settings enabled_accessibility_services+a11y enabled、pm grant POST_NOTIFICATIONS
  - 备份恢复：`clipdown_cookies.xml`（520B，含 IG sessionid，curl XML 容忍 CRLF）与 `clipdown_settings.preferences_pb`（2461B，二进制 proto）→ 用 `adb shell run-as ... cat`（走 pty **会做 LF→CRLF 翻译**）→ 通过 `adb root` push + chown 10206:10206
- **新坑 14（写入 HANDOFF）**：app 启动即崩溃 `Unable to parse preferences proto / While parsing a protocol message, the input ended unexpectedly in the middle of a field`。原因：`adb shell run-as cat` 对二进制 proto 插入 \r，导致 DataStore 解析失败（hex 头 0d0a b112 0d0a ...）。**正确做法：`adb exec-out run-as <pkg> cat <path>` 或 `adb exec-out "cat <path>"`（exec-out 不走 pty）**；XML 类文本 SP 受 CRLF 影响小可忽略。修复：删除损坏的 datastore 文件，cookie SP 保留（XML 容忍 \r）。
- **R8 运行时确认**：删除损坏 datastore 后，release 启动正常，无新崩溃；MainActivity 渲染、悬浮窗（SYSTEM_ALERT_WINDOW 窗口在 dumpsys window 列出 ✓）、三页导航齐全（首页/下载/设置）→ **R8 未破坏运行时**
- **解析链路验证**：
  - X syndication 首次（SEND 通道）：解析成功 ✓（title "Chez ISS - episode deux..." / 3 视频变体 / author "Anil Menon" / resolverId "x-syndication-v1"）—— 验证 release 下解析+网络+JSON+R8 全部通过
  - X syndication 后续：公开接口返回 404（模拟器出口 IP 受 X 公共接口限速）
  - B 站：`播放地址接口返回异常格式`（B 站 playurl 在代理 IP 风控）
  - IG 未测（坑 13 测试纪律：风控期优先非 IG）
- **未完成（环境限制，非 release 回归）**：下载闭环 + 图集竖条。下载引擎由 30 例单测兜底（HttpFileDownloader 续传/RANGE/M3u8 AES/ICS/MediaRemuxer/TaskModels/DownloadController sanitizeTaskUrl），网络环境可控时再回加压测试
- **a11y UI 状态**：`去开启` 即使 enabled_accessibility_services 已 set——模拟器未触发系统级 toggle 回调，应用本地检测仍判未开；切真机后通过系统设置手动开启一次后稳定
- **测试注入不稳**：`am start-foreground-service` 需要 adbd 为 root，否则返回 `not exported from uid 10206`；SEND 通道到 MainActivity 受 Android 10+ 后台启动活动约束、`noHistory` ShareTargetActivity 异步 startActivity 时机竞争影响，模拟器上不一定带 UI 前台—— 真机/分享卡片走系统剪贴板更稳
- **改动文件**：`.zcodeignore`（新增提交：项目级 agent 忽略配置）；`HANDOFF.md`（坑 14 + 状态更新）；`PROGRESS.md`（看板 + 阶段 30）
- **下一步**（真机可解决模拟器所有未验证项）：①X 单视频下载闭环（家宽绕过 X 404）②阶段 29 四项气泡交互（X 图集竖条 / 拖到下半屏向上生长 / 再点收起 / IG 冷却文案）③IG 风控恢复后阶段 25 复测 ④keystore 备份提醒：`app/signing/clipdown.jks`（密码 clipdown2026）务必备份，否则同包名无法升级签名
- 本次文档更新时间：10.06 11:30

## 阶段 31 小红书链路测试与修复（图集去重 / 视频帖净化 / 短链中转页 / WebView 收口 + 失效页守卫） [计划时间：10.06 11:50 BY ZCode][完成时间：10.06 12:35 BY ZCode]
- 用户要求"开始对小红书链路的测试和修复"→ 代码审查（解析器/夹具/路由/短链/WebView）+ 单元用例 + 设备端端到端验证
- **修复 1 图集去重（P0）**：小红书同一张图同时下发 `urlPre`（预览）与 `urlDefault`（默认画质），两者**文件 ID 相同**（`.../notes_pre_post/<fileId>!nd_prv_/!nd_dft_`），旧实现按 URL 字符串去重 → **每张图产出 2 项**（夹具 2 图 → 4 项，竖条里每张图重复）。改为 `extractImages` 用 `linkedMapOf<fileId, url>` 去重，**先灌 urlDefault、urlPre 只补空缺**（保证留下更高画质那份）
- **修复 2 视频帖净化（与 IG 修复 10c 同源）**：视频笔记 SSR 同时有 `masterUrl` 与 `imageList`（封面帧），旧实现产出 `[视频, 封面图]` → `isAlbumMultiSelect=true` → 弹图集竖条、跳过自动下载，用户下到"视频+封面图"。改为 **videos 非空时 images 直接置空**；图集帖无 masterUrl 不受影响
- **修复 3 短链中转页**：`ParserEngine.expand()` 原本只认 3xx Location；xhslink 等短链常返回 200 的 meta refresh / JS 跳转页，OkHttp 不跟随 → 原样返回短链 → 解析必然失败。新增 `redirectFromHtml()`（meta refresh → location.href → location.replace）并在 GET 兜底分支使用
- **修复 4 WebView 抓取收口（设备实测暴露，P0）**：`startPolling` **只在命中视频标记时才回调**，而小红书图集页根本没有视频标记 → 必然白等 40s 超时、`html=null`，再退回被阿里云 WAF 拦掉的 OkHttp → **图集永远解析失败**。改为三路收口：命中媒体标记立即返回 / 页面长度连续两次不变判稳定提前返回 / 最后一次轮询兜底交出。**实测 40s → ~8s，且 `fetch 结束：html=274649`（此前恒为 null）**
- **修复 5 失效笔记守卫**：小红书对失效/缺 xsec_token 的笔记不返回 4xx，而是渲染「小红书 - 你访问的页面不见了」或跳探索页（「小红书 - 你的生活兴趣社区」），SSR 里是推荐流 → 无守卫会把推荐流封面图当图集返回。按 title 判定直接失败（文案提示重新复制带 xsec_token 的分享链接）；另加 `noteDetailMap` 守卫兜住"有 SSR 但不是笔记详情页"
- **过程中定位的环境级坑（非代码缺陷，写入 HANDOFF 坑 15）**：阶段 30 用 root push 恢复 datastore 只 chown 了文件，DataStore 写 `.tmp` 时 `EACCES` → **主线程 FATAL → 进程被杀 → WebView 抓取永久挂起**（日志停在 `fetch 开始`）。`pm clear` + 只恢复 xml cookie + `chown -R` 后恢复
- 测试：parser **48 例全绿**（原 40 + 图集去重 1 + 视频帖净化 1 + 失效页守卫 1 + 非笔记详情页 1 + 短链中转页 4）；downloader 30 例全绿；设备端端到端：`via=webview code=200 len=274649 state=true` → `笔记不存在或链接已失效` 明确文案，**0 崩溃**
- 改动文件：`parser/parsers/XiaohongshuParser.kt`（修复 1/2/5）、`parser/core/ParserEngine.kt`（修复 3 `redirectFromHtml`）、`app/clip/WebViewHtmlFetcher.kt`（修复 4）、`parser/test/.../XiaohongshuParserTest.kt`（+3 例）、`parser/test/.../core/ParserEngineTest.kt`（新建 +4 例）、`HANDOFF.md`/`PROGRESS.md`
- **未完成**：小红书**真实成功下载**未验证——测试夹具的笔记 ID `6ab7ffb0000000000b006b27` 已被小红书判为失效页（设备落盘页 title 取证），公开搜索取不到带 xsec_token 的有效笔记链接；需用户从 App 分享一条真实笔记链接（含 xsec_token）后复测图集竖条与视频下载
- 下一步入口：拿到有效小红书链接 → 手动粘贴/分享注入 → 验证①图集竖条图片数与实际一致（去重生效）②视频帖直接自动下载（无竖条）③老链接失效时给出可操作文案
- 本次文档更新时间：10.06 12:35

## 阶段 32 小红书真实链接联调（xhslink.cn / 短链 a href / 移动端 UA 与结构 / 反爬指纹结论） [计划时间：10.06 13:30 BY ZCode][完成时间：10.06 14:20 BY ZCode]
- 用户提供真实分享链接（单图 `xhslink.cn/o/7mDR2JlydL0`、多图 `xhslink.cn/o/1kijoisLVUe`；第三条"视频"链接与多图**完全相同**，应为复制遗漏），联调中又发现 5 个问题：
- **修复 6（阻塞级）短链域名漏登记**：`Platform.XIAOHONGSHU.hosts/shortHosts` 只有 `xhslink.com`，而 App 分享实际下发 **`xhslink.cn`** → 整条链接被判"暂不支持该链接"。补登记 + `UrlUtilTest` 断言
- **修复 7（阻塞级）短链页是纯 `<a href>`**：`xhslink.cn/o/xxx` 返回 **200 + `<a href="https://www.xiaohongshu.com/discovery/item/<id>?xsec_token=...">`**，既非 3xx 也非 meta refresh/JS 跳转 → `redirectFromHtml` 增加"页面里首个落在已知平台域名的绝对链接"分支（排除自身域名与 CDN），并还原 `&amp;`
- **修复 8 移动端 UA**：小红书对**桌面 UA 的笔记页一律 302 /login**，移动端 UA 才返回带 SSR 的页面。解析器直连改用 `headersFor(platform, desktop=false)`；WebView 按站点选 UA（小红书用 Android Chrome 移动 UA，其余保持桌面 UA）
- **修复 9 移动端 SSR 结构适配**：移动端**没有 `noteDetailMap`**，图片在 `imageList[].url`（带 `fileId`），标题 `title`，作者 `user.nickName`。新增 `noteWindow`（以首个 imageList 为中心 ±3000 字符，避开推荐流）、`imageListSlice`（括号配对取数组切片）、`authorFromWindow`（取 `"user":{` 作用域内的 nickName，避免取到 `atUserList` 里被 @ 的人）
- **修复 10 正则截断**：`sns-webpic` 正则原用惰性量词 + 可选 query，会把 URL 截断成 `.../2` → 改贪婪匹配；且**结构化提取有结果时不再做全页正则兜底**（否则把紧随其后的推荐流封面当成图集）
- **修复 11 直连优先、WebView 兜底**：原实现 WebView 优先；改为直连（移动端 UA）优先，直连结果不含 `imageList` 时才退 WebView。新增 `直连结果` 诊断日志（code/len/finalUrl/imageList）
- **环境结论（写入 HANDOFF 坑 17）**：同一 URL、同一移动端 UA、同一请求头，**主机 curl 得 200（含 imageList），App 内 OkHttp 与 WebView 都被 302 到 `/login`**（`直连结果：code=200 finalUrl=.../login?redirectPath=... imageList=false`）。已排除 xsec_token 过期（1.5h 后主机仍 200）、`apptime`/`share_id` 被 normalize 剥离、UA 与 client hints 不一致、WebView 残留 cookie、模拟器代理、请求头组合 → **反爬含客户端指纹维度**。唯一可行动路径：用户提供 `web_session` Cookie（设置页入口已支持）
- 测试：parser **56 例全绿**（阶段 31 的 48 + 短链域名/分享文案召回 2 + 移动端结构/多图 2 + 短链 `<a href>`/CDN 排除/自身域名 3 + 登录墙文案 1）
- 改动文件：`parser/model/Platform.kt`、`parser/core/ParserEngine.kt`、`parser/parsers/XiaohongshuParser.kt`、`app/clip/WebViewHtmlFetcher.kt`、`parser/test/.../UrlUtilTest.kt`、`parser/test/.../XiaohongshuParserTest.kt`、`parser/test/.../core/ParserEngineTest.kt`
- **未完成**：小红书图集/视频的真实成功下载（环境反爬挡住）→ 需 ①用户提供小红书 Cookie ②或真机验证（用户真实 IP + 真机栈）
- 下一步入口：拿到 Cookie → 设置页粘贴 → 重跑单图/多图链接 → 验证竖条张数与实际一致（单图 1 张、多图 3 张）→ 再取一条**真实视频链接**验证自动下载
- 本次文档更新时间：10.06 14:20

## 阶段 33 小红书视频帖结构取证与真实夹具（masterUrl 实测可下载） [计划时间：10.06 15:37 BY ZCode][完成时间：10.06 15:45 BY ZCode]
- 用户补发视频帖链接 `xhslink.cn/o/22TlIMvD96D`（分享文案以 🇲🇴 开头）→ 短链展开为 `type=video` 的 `discovery/item/6ac05cc4000000001b02d811`
- **结构取证（主机抓取真实页面）**：
  - 视频在 `video.media.stream.h264[].masterUrl` + `backupUrls`（`width/fps/vmaf` 同级），**位置在 imageList 窗口之外**——所以 `extractVideos` 必须全页扫描（现有实现正确）
  - 封面在 `imageList[0].infoList[]`，`imageScene` 为 `H5_DTL`（`!h5_1080jpg`）与 `H5_PRV`（`!style_xxx`）两形态但 **fileId 相同** → 按文件 ID 去重后仅 1 张
  - 标题就是旗子 emoji **`🇲🇴`**（与用户分享文案开头一致，互为佐证），作者 `user.nickName = 菠萝烤狗`；`imageList` 窗口内各只出现 1 次 → 窗口策略对视频帖同样成立（窗口外的推荐流标题「你人挺不错的 去法国排队吧」被正确排除）
- **真实可下载性验证（关键）**：把解析规则提取出的 `masterUrl` 拿去做 HTTP 实测 → **`200 OK` / `Content-Type: video/mp4` / `Content-Length: 3792890`（3.6MB） / `Accept-Ranges: bytes`**；Range 请求 → **`206` 262144 字节**（下载器续传能力可用）。即**解析产物是真实可下的 MP4**，链路中"解析 → 下载"的接口契约成立
- **新增真实夹具与用例**：`parser/src/test/resources/xhs_video_note.html`（按真实页面结构裁剪，含 h264/masterUrl/backupUrls、infoList 双形态封面、feed 干扰项）+
  - `视频帖 只出视频且封面图不落媒体`：断言 1 个视频 / 0 张封面图 / `isAlbumMultiSelect=false`（走自动下载）/ URL 指向 xhscdn / 标题 `🇲🇴` / 作者 `菠萝烤狗`
  - `extractFirst 从 emoji 开头的分享文案中召回链接`（🇲🇴 + 中文 + 链接，用户实际文案形态）
- 测试：parser **58 例全绿**（阶段 32 的 56 + 视频帖真实夹具 1 + emoji 分享文案 1）
- 改动文件：`parser/src/test/resources/xhs_video_note.html`（新建）、`parser/test/.../XiaohongshuParserTest.kt`、`parser/test/.../UrlUtilTest.kt`、`HANDOFF.md`/`PROGRESS.md`
- **未完成**：视频帖在**设备上**的端到端下载仍被环境反爬挡住（与阶段 32 同因：App 侧请求落 `/login`）→ 需 Cookie 或真机
- 下一步入口：拿到 Cookie / 真机 → 单图（1 张）+ 多图（3 张）+ 视频帖（无竖条、直接自动下载）三例一起复测
- 本次文档更新时间：10.06 15:45

## 阶段 34 远程快速更新链路（一键发布脚本 + 应用内检查更新） [计划时间：10.06 15:41 BY ZCode][完成时间：10.06 16:05 BY ZCode]
- 用户需求：测试期需频繁更新，要求"推流更新 / GitHub Release，支持远程快速更新"
- **版本号外置**：新建 `version.properties`（versionCode/versionName），`app/build.gradle.kts` 读取；发布脚本自增（不再硬编码在 gradle 里）
- **更新通道可配置**：`gradle.properties` 的 `clipdown.updateRepo` / `clipdown.apkAssetName` → 生成 `BuildConfig.UPDATE_MANIFEST_URL` / `UPDATE_APK_URL`；**改发布仓库不用改代码**
- **清单走"最新发布固定链接"**：`releases/latest/download/update.json` —— 不调 GitHub API、不需要 token、无匿名速率限制；**代价是发布仓库必须公开**（私有仓库 Release 资产无法匿名下载），源码仓库可保持私有
- **一键发布脚本** `scripts/publish-release.sh`：自增版本号 → `assembleRelease` + parser/downloader/app 全量单测 → 生成 `build/dist/clipdown-release.apk` 与 `update.json`（versionCode/versionName/notes/apkUrl/sha256/sizeBytes/mandatory）→ `gh release create --latest` 上传。支持 `--bump/--notes/--repo/--dry-run/--skip-build/--create-repo`；已跑 `--dry-run` 验证（版本 1.0.1(2)→1.0.2(3)、sha256/体积/JSON 均正确）
- **CI** `.github/workflows/release.yml`：手动 dispatch（可选自增方式+说明）或 `git tag v*` 触发；还原 keystore secrets → 构建+全量单测 → 生成资产 → `gh release create --latest`；未配 secrets 时明确告警（会落 debug 签名导致老用户无法覆盖安装）
- **应用内更新（新增 4 个文件）**：
  - `update/UpdateManifest.kt`：清单模型 + `UpdateInfo`（`isNewerThan` 只看 versionCode、`sizeText()`）
  - `update/UpdateRepository.kt`：OkHttp 拉清单（404 = 尚无发布 → 视为无更新而非报错）+ `parse()` 独立可测 + 当前版本读取
  - `update/ApkInstaller.kt`：下载到 `cacheDir/update/`（已存在且体积匹配则复用）→ 可选 sha256 校验 → FileProvider + `ACTION_VIEW(application/vnd.android.package-archive)` 拉起系统安装器；含"安装未知应用"权限检查与跳转设置
  - `update/UpdateCenter.kt` + `update/UpdateUi.kt`：进程级单例状态（Compose state）供设置页与启动提示共用；设置页新增「关于与更新」区块（当前版本/通道/检查更新/下载并安装/进度条/首次需开安装权限引导）；启动静默自检 + 发现新版本弹一次提示框（可"稍后"）
- **接线**：`ClipDownApp.onCreate` → `UpdateCenter.install(this)`；`MainActivity` → 启动 `check()` + `UpdateLaunchDialog()`；`SettingsScreen` → `item { UpdateSection() }`；Manifest 增 `REQUEST_INSTALL_PACKAGES` + FileProvider；新增 `res/xml/file_paths.xml`
- **覆盖安装前提**：新旧 APK 签名一致（同一 keystore）→ 系统原地升级并保留数据；签名不同会提示"应用未安装"
- 测试：`:app:testDebugUnitTest` **4 例**（清单完整解析 / apkUrl 缺省回落固定链接 / 忽略未知字段 / 版本比较只看 versionCode）+ `:app:assembleDebug` 全绿
- 改动文件：`version.properties`（新建）、`gradle.properties`、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/file_paths.xml`（新建）、`app/.../update/{UpdateManifest,UpdateRepository,ApkInstaller,UpdateCenter,UpdateUi}.kt`（新建）、`app/.../ClipDownApp.kt`、`app/.../ui/MainActivity.kt`、`app/.../ui/settings/SettingsScreen.kt`、`app/src/test/.../UpdateManifestTest.kt`（新建）、`scripts/publish-release.sh`（新建）、`.github/workflows/release.yml`（新建）
- **待用户决策（阻塞首次发布）**：发布仓库 `Gorilla-Kevv/clipdown-dist` 尚不存在。**必须是公开仓库**应用侧才能匿名下载；创建公开仓库属于对外可见操作，需用户确认（或改为把主仓库转公开）。确认后 `bash scripts/publish-release.sh --create-repo` 一步完成建仓+首发
- 下一步入口：①确认发布仓库方案并首发 ②真机安装首发 APK ③后续每次更新只需 `bash scripts/publish-release.sh --notes "…"`，测试机在应用内一键覆盖安装
- 本次文档更新时间：10.06 16:05
