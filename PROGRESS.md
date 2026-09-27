# PROGRESS

## 阶段 2：环境迁移验证（已完成 · 2026-09-27）
- 已完成：开发环境整体迁移至 `F:\AndroidDev`（JDK17 / SDK / Gradle 8.9 / 依赖缓存）；用户级环境变量与 PATH 已确认指向 F 盘；`local.properties` 的 `sdk.dir` 与统一构建脚本 `F:\AndroidDev\build.bat` 已更新；清理了迁移带入的临时目录（wtmp / probe_tmp / cmdline-tmp）与临时脚本，保留 `install_sdk.bat`、`setup_env.ps1`、`repo.xml`、`sdk\.sdk`（sdkmanager 缓存）
- 未完成：git 初始化与提交、单测、release/签名、真机回归（同阶段 1）
- 改动文件：`local.properties`（F 盘路径）、`F:\AndroidDev\build.bat`（重写指向 F 盘）、`HANDOFF.md` / `README.md` / `PROGRESS.md`（文档同步）
- 测试结果：`F:\AndroidDev\build.bat :app:assembleDebug` 全绿（60 任务），`app-debug.apk` 正常产出
- 风险：`F:\AndroidDev\sdk\.sdk` 为 sdkmanager 内部缓存目录，勿删除；再次迁移环境需同步改环境变量、`local.properties`、`build.bat` 三处
- 下一阶段入口：读 `HANDOFF.md` 的「下一步」清单，从 `git init + 首次提交` 开始

## 阶段 1：初始实现（已完成）
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

## 阶段 2：git 初始化与首次提交（已完成）
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

## 阶段 3：AVD 模拟器环境 + UI 冒烟测试（已完成）
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

## 阶段 4：模拟器代理 + YouTube 全链路实测（已完成）
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

## 阶段 5：Instagram / X 模拟器实测（已完成）
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
