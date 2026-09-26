# 剪存 ClipDown

Android 端「剪贴板识别 → 网址解析 → 下载」应用。复制 Instagram / X / 小红书 / Facebook / 抖音 / B站 等平台的链接后，悬浮窗会自动弹出半透明毛玻璃提示卡，一键保存到本地。

解析能力与下载器均为自研实现，不依赖任何第三方下载 SDK。

---

## 一、快速开始

### 1. 开发环境（已搭建完毕）

| 组件 | 版本 | 路径 |
| --- | --- | --- |
| JDK | Temurin 17.0.20.1 | `C:\AndroidDev\jdk\jdk-17.0.20.1+1` |
| Android SDK | cmdline-tools 23.0 / platform 34 / build-tools 34.0.0 | `C:\AndroidDev\sdk` |
| Gradle | 8.9（二进制 + Wrapper） | `C:\AndroidDev\gradle-8.9` |
| AGP / Kotlin | 8.7.3 / 2.0.21 | 见 `build.gradle.kts` |

环境变量 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`GRADLE_USER_HOME` 已写入用户级环境变量，PATH 已追加 JDK / Gradle / platform-tools。

> 注意：本机自带的 JDK 24 版本过高（AGP 8.x 仅支持 JDK 17/21），构建统一使用上面这套 JDK 17。

### 2. 构建

```bash
# Windows（已配置好环境变量时）
gradlew.bat assembleDebug

# 或显式指定（推荐，避免与系统 JDK 冲突）
set JAVA_HOME=C:\AndroidDev\jdk\jdk-17.0.20.1+1
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 3. 使用步骤

1. 安装后打开应用，在首页授权 **悬浮窗**；
2.（可选但推荐）开启 **无障碍服务** —— Android 10+ 后台无法读取剪贴板，开启后才能在其它 App 内复制时自动识别；
3. 在 Instagram / 小红书 等 App 里点"分享链接"，或复制链接后点击悬浮球；
4. 弹窗出现后选择清晰度 → 下载；文件保存到 `相册/影片/ClipDown`。

**最可靠的通道**：在目标 App 中选择「分享 → 剪存」，无需任何权限。

---

## 二、工程结构

```
mobileSrcDownloadplugin/
├── app/                       UI 层（Compose + 悬浮窗 + 剪贴板通道）
│   └── src/main/java/com/clipdown/app/
│       ├── ClipDownApp.kt              应用入口：初始化解析内核与下载引擎
│       ├── clip/                       四通道剪贴板获取 + LinkCenter
│       ├── floatwindow/                悬浮球 + 毛玻璃弹窗（WindowManager）
│       ├── ui/                         首页 / 下载 / 设置 / 导航
│       └── data/                       设置（DataStore）与平台 Cookie
├── parser/                    解析内核（纯 Kotlin/JVM，可单测）
│   └── src/main/kotlin/com/clipdown/parser/
│       ├── core/                       ParserEngine、UrlUtil、PlatformRegistry、RemoteResolver
│       ├── parsers/                    9 个平台解析器 + 通用解析 + HtmlUtil
│       ├── stream/                     m3u8 播放列表模型与解析
│       ├── model/  config/  http/      数据模型、配置、HTTP 抽象
├── downloader/                下载引擎（Android Library）
│   └── src/main/kotlin/com/clipdown/downloader/
│       ├── DownloadController.kt       对外唯一门面
│       ├── engine/                     队列引擎、断点续传、HLS、封装合并
│       ├── db/  storage/  notify/      任务持久化、MediaStore 入库、通知
└── docs/                      设计文档（调研 / 架构 / 流程 / 界面 / 适配）
```

---

## 三、设计文档

| 文档 | 内容 |
| --- | --- |
| [01-解析方案调研.md](docs/01-解析方案调研.md) | yt-dlp / cobalt / gallery-dl 等方案对比与整合选型、各平台解析要点 |
| [02-架构与模块设计.md](docs/02-架构与模块设计.md) | 目标平台清单、三模块分层架构、页面与功能模块划分、关键取舍 |
| [03-剪贴板监听与解析流程.md](docs/03-剪贴板监听与解析流程.md) | Android 10+ 剪贴板限制、四通道设计、URL 提取归一化、解析主流程 |
| [04-下载界面与交互设计.md](docs/04-下载界面与交互设计.md) | 毛玻璃弹窗视觉规格与状态机、下载管理页、通知、任务类型与落盘 |
| [05-平台适配策略.md](docs/05-平台适配策略.md) | 通用适配层 + 9 个平台的特化策略、失败降级与维护策略 |

---

## 四、能力一览

**解析**
- 9 个平台解析器 + 通用网页兜底，SPI 注册表可插拔
- 短链自动展开（xhslink / t.co / youtu.be / b23.tv / v.douyin.com / vm.tiktok.com）
- 三级降级：本地直连 → 公开接口 → 远端解析服务（可自建 cobalt / yt-dlp 服务端）
- 登录态增强：用户提供 Cookie（仅存本机）即可获取原画质
- B站 WBI 签名完整实现，无需代理

**下载**
- 自研队列引擎：并发控制、暂停/继续/取消、指数退避重试
- Range 断点续传
- HLS：并发拉分片 + AES-128 解密 + 拼接 + 尽力封装 MP4
- DASH：视频轨/音频轨分离下载后 `MediaMuxer` 合并
- MediaStore 入库（Android 10+ 免存储权限）+ 前台服务与进度通知

**交互**
- 悬浮球（可拖动、位置持久化、未读角标）
- 毛玻璃弹窗（Android 12+ 系统级背景模糊，低版本半透明降级）
- 四通道链接获取 + 15s 去重，避免打扰

---

## 五、合规说明

- 仅处理用户自己复制/分享的公开内容链接，不提供批量抓取、不绕过付费墙。
- 平台 Cookie 由用户自行提供，仅保存在本机，不参与任何外发请求。
- 远端解析服务地址可由用户替换，应用不硬编码第三方服务依赖。
