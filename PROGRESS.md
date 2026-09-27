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
