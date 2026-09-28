# PLAN — 阶段 14-18：气泡状态机与自动下载流水线

来源：用户 2026-09-28 交互需求（6 点）+ HANDOFF 阶段 11-13 现状。
硬指标：动画 60fps、状态切换 0.3s 过渡、交互响应 <100ms。

## 现状差距（对照 HANDOFF 结构）

| 需求 | 现状 | 差距 |
| --- | --- | --- |
| 复制后气泡状态动效 | 气泡静态（绿环+绿点） | 无状态机/无动画 |
| 复制后自动解析 | 新链接已自动解析（observeLinks） | ✅ 已有，需接气泡动效 |
| 解析计数徽标 | 无 | LinkCenter 单值语义，需队列计数 |
| 解析完自动下载 | 需点「开始下载」 | 新增自动 enqueue 流水线 |
| 下载环形进度 | 无 | Canvas 进度环 |
| 单击/双击气泡 | 单击=迷你面板 | 区分双击（跳主界面）与单击（详情/手动模式） |
| 图集全选/多选 | 仅 chip 单选 | 主界面+弹窗多选 UI |
| 并发解析 | showAndParse 单 parseJob（新解析 cancel 旧解析） | 解析队列化 |
| reels 403 | 未实测 IG 下载 | 定位 Referer/URL 过期/头传递 |

## 阶段 14：气泡状态机与动效（纯视觉，风险最低，先行）
- 目标：BubbleContent 重构为 Compose 状态驱动动画（Canvas 画环，不用重布局）
  - 状态：空闲=蓝色呼吸光圈 / 解析中=黄色旋转光圈 / 解析成功=绿色闪现 1-2s / 解析失败=红色闪现+提示 / 下载中=环形进度 0-100% / 下载成功=紫色 2s / 回到空闲
  - 过渡 0.3s（animateColorAsState / animateFloatAsState）；旋转/呼吸用 InfiniteTransition（60fps）
  - 左上角解析计数徽标（PendingCount）
  - 单击/双击区分：pointerInput detectTapGestures（onTap / onDoubleTap，双击窗口 ~250ms 内不触发单击——单击行为延迟到双击窗口过期后执行）
- 改动文件：`FloatingWindowService.kt`（BubbleContent + 点击手势 + uiState 扩展 BubblePhase）、新增 `floatwindow/BubblePhase.kt`
- 验收：adb 模拟各状态（解析/失败/下载）逐个截图/录屏核对；动画不掉帧（ dumpsys gfxinfo）
- 风险：overlay 窗口动画性能（x86 模拟器 gpu host 应可 60fps）；双击窗口内单击延迟 250ms 属预期行为

## 阶段 15：自动解析 → 自动下载流水线（核心行为变更）
- 目标：复制新链接 → 气泡黄圈自动解析 → 绿圈 → **自动 enqueue 下载**（无 UI 确认）→ 下载进度环
- 设计决策（默认值，可被设置页覆盖）：
  - 图集/多资源：**不自动下载**，弹选择卡（全选/多选，进阶段 18 的选择 UI；阶段 15 先保持现状手动）
  - 视频/单图：自动下载；下载中单击气泡=详情小卡（文件名/大小/进度），双击=主界面
  - 空闲时单击气泡=迷你面板（手动粘贴模式，即现 Mini 面板）
  - 识别记忆（seen_links）保留：已识别链接不自动解析不自动下载；下载完成后写 suppressedUrl 机制改为按 URL 集合
  - IG 风控保险丝：自动下载默认仅 WiFi（复用 settings.wifiOnly）+ 失败退避
- 改动文件：`FloatingWindowService.kt`（observeLinks → 自动 enqueue；downloadActive 守卫从"单任务"改"按 taskId 集合"）、`PopupUiState.kt`（下载详情卡状态）、`data/SettingsRepository.kt`（autoDownload 开关）
- 风险：自动下载改变现有「用户确认」习惯（加设置开关默认开）；IG 风控放大（WiFi 限制+失败退避缓解）；suppressedUrl/downloadActive 单值守卫必须重构为集合，否则多任务互踩
- 验收：复制新 reel 链接 → 气泡黄→绿→紫全链路无点击；`Movies/ClipDown/` 落盘；已见链接复制无任何反应

## 阶段 16：并发解析与多任务（配合"5 秒内连续复制多个链接"）
- 目标：解析队列化——每条 detected 链接独立 parseJob（并行 N=2-3），互不取消；气泡徽标显示"解析中 N / 待下载 M"
- 现状坑：FloatingWindowService 单 parseJob + uiState 单弹窗语义，多链接互踩；LinkCenter.last 单值
- 设计：新增 `ParseQueue`（Service 内）：detected → 入队 → 并发 parse → 结果入下载队列；弹窗只展示"最新"结果，其余状态全在气泡徽标；LinkCenter 的 15s 去重保留
- 改动文件：`FloatingWindowService.kt`（解析队列重构）、`clip/LinkCenter.kt`（detected 携带序号/支持多链接）、`BubblePhase`
- 验收：5 秒内连续复制 2-3 个不同平台链接 → 徽标计数正确 → 全部落盘
- 风险：IG 风控放大（并发请求数×N）；弹窗与气泡状态一致性
- 依赖：阶段 15 的守卫重构先行

## 阶段 17：reels 下载 403 修复
- 目标：IG 视频（video_versions URL）下载不再 403
- 排查序：①下载请求头（Referer=instagram.com 已带，核对 MediaItem.headers 是否真的传到 HttpFileDownloader）②URL 时效：CDN 链接带 expiry 签名，解析→下载间隔过长即 403（自动下载流水线天然缓解）③可能需要 Cookie 头随下载（CookieStore 传入）④blob: URL 误判（video_versions 是直链，不会是 blob）
- 改动文件：`downloader/engine/HttpFileDownloader.kt`（若需补默认头）、`InstagramParser.kt`（headers 传递核对）
- 验收：reel 解析后自动下载 → ftyp isom 真 MP4 落盘
- 依赖：阶段 15（自动下载缩短 URL 时效窗口）

## 阶段 18：图集全选/多选下载 UI
- 目标：图集帖弹选择卡：全选下载 / 多选（chip 多选模式）/ 仅视频
- 改动文件：`ClipPopupContent.kt`（chip 多选态）、`FloatingWindowService.kt`（多选 enqueue 批量）、`MainActivity` 结果卡同步多选
- 验收：12 张图集全选 → 12 个任务全部完成；多选 3 张 → 仅 3 个任务
- 依赖：阶段 15 的批量 enqueue 能力

## 执行顺序与依赖
14（纯视觉）→ 15（流水线+守卫重构）→ 16（并发）→ 17（403）→ 18（多选 UI）；17 可提前与 15 并行（只依赖下载链路）。

## 每阶段收尾
`:parser:test` 全绿 + `assembleDebug` + 模拟器实测 + 更新 PROGRESS + 提交推送。
