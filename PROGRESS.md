# PROGRESS

## 修复 10 + 竖条重排（用户实测反馈修正 · 2026-09-28，提交 e7f7a95）
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

## 阶段 20 返工：竖向窄条 + toggle 交互（已完成 · 2026-09-28，用户反馈修正）
- 用户反馈（附截图）：①条形应为**竖向**而非横向；②窄条**跟随气泡移动**、点气泡拉开/再点收起（组件语义）；③**Mini 默认弹窗同样竖条化**，去掉全屏模糊+居中大卡
- 已完成（提交 6f88d9f）：
  - **竖向窄条**：128dp 宽竖排容器（SideBarContainer），Mini（logo/剪存/hint/识别按钮）与图集（首图缩略图+计数+全选行+下载+挑图/收起+2 列缩略图网格）两种内容共用；去掉全屏 scrim 与系统模糊——窗外内容清晰可见
  - **toggle 语义**：onBubbleClick 改为"已有弹层→收回；下载中→详情竖条；空闲→识别"。点气泡开、再点气泡收
  - **跟随拖拽**：sideBarWinParams 引用 + followBubble()（与初始定位同一相对公式，绝对对齐无漂移），气泡 ACTION_MOVE 时同步移动条形窗口
  - 窗口定位：气泡**左侧**（x=气泡左缘-条宽-8dp，钳制屏内），顶缘对齐气泡顶-20dp；展开网格向下延伸不遮挡气泡；Mini 与图集切换形态时窗口重建
  - 清理：旧 MiniBody（全屏居中版）删除，material Checkbox 改为紧凑自定义勾选框（竖条空间）
- 测试结果：构建+39 例全绿；模拟器实测：点气泡→Mini 竖条弹出（贴气泡左侧、无遮罩）✓、再点气泡→收回 ✓（截图核对）；图集竖条与拖拽跟随待用户实测
- 下一阶段入口：真机回归（累积项）→ release 签名 → `:downloader` 单测

## 阶段 19+20：下载页分组与气泡条形选择卡（用户需求包 · 代码完成 · 2026-09-28）
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

## 阶段 18：图集全选/多选下载 UI（代码完成 · 2026-09-28，装机待实测确认）
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

## 阶段 17：IG reels 下载 403 修复（已完成 · 2026-09-28，用户复测通过）
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

## 阶段 16：并发解析可见性 + 三徽标（已完成 · 2026-09-28）
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

## 阶段 15：自动解析→自动下载流水线（已完成 · 2026-09-28）
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

## 阶段 14：气泡状态机与动效 + 单双击手势（已完成 · 2026-09-28）
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

## 阶段 6：IG Cookie 注入 + 登录态解析攻坚 + IG App 安装（已完成）
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

## 阶段 7：X 视频 + 小红书 + IG 真实场景实测（已完成）
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

## 阶段 8：小红书 Cookie 实测 + WAF 指纹定性（已完成）
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

## 阶段 10：WebView 抓取实现 + IG reel 实测（**基础设施完成，媒体提取未通**）
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

## 阶段 9：`:parser` JVM 单测（已完成）
- 已完成：
  - **7 个测试套件 34 个用例全绿**（JUnit4，`:parser:test` 33s）：UrlUtil 8 / M3u8 4 / PlatformRegistry 6 / X 5 / Youtube 4 / Instagram 3 / Xiaohongshu 4
  - 真实数据夹具入库 `parser/src/test/resources/`：syndication_video_tweet.json（真实 API 响应+已知 token 答案 52qqmtfi7esad5b）、piped_streams.json（真实 Piped 响应）、xhs_note.html（真实笔记页 104KB）
  - **修复 5（真 Bug）：`HtmlUtil.jsonField` 不支持 `\uXXXX` 转义**——小红书 SSR 用 `\u002F` 表示斜杠，`[^"\\]+` 遇反斜杠即断导致整段匹配失败；pattern 改为 `(?:[^"\\]|\\.)+?` + unescapeJson 增加 `\uXXXX` 通用解码（IG 等平台同样受益）
  - **修复 6（小）：`UrlUtil.normalize` 未去除路径尾斜杠**（query 存在时 trimEnd 只作用于串尾）→ 路径 `trimEnd('/')`，链接去重更稳
- 改动文件：`parser/src/test/**`（8 文件+3 夹具）、`HtmlUtil.kt`、`UrlUtil.kt`
- 测试结果：`:parser:test` 全绿（34/34）
- 下一阶段入口：WebView 抓取方案（小红书）；真机回归；`:downloader` 单测（可复用 FakeHttp 思路）

## 阶段 11：悬浮窗复制触发修复 + 弹窗内下载闭环（已完成 · 2026-09-27）
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

## 阶段 12：气泡交互重构 + IG Cookie 注入（已完成 · 2026-09-28）
- 用户四点需求全部落地（提交 `580b0d3`）：
  1. **气泡纯入口化**：头像 logo（用户图片裁切 256px，`drawable-nodpi/bubble_logo.png`）+ 待处理绿点；点击只展开迷你面板（`PopupUiState.Mini`：logo + "识别链接"按钮 + hint 提示），不再借道读剪贴板/不再自动解析/不再跳应用
  2. **跳转入口**：解析弹窗新增描边按钮「跳转至剪存应用」；"下载"更名"开始下载"
  3. **后台下载**：下载中点空白收起弹窗，任务在 DownloadService 继续；终态簿记与弹窗可见性解耦；collect 终态自终止（修泄漏）
  4. **识别记忆**：DataStore `seen_links`（StringSet，上限 400）持久化——已识别链接不再自动弹窗，仅迷你面板手动识别（绕过记忆 + gate force 提交）；`UrlUtil.normalize` 剥离 X `/mediaViewer` 变体，杜绝视频播放期间绕过记忆反复弹窗
- 附带修复：`ClipGateActivity` 独占任务栈（taskAffinity=""）消除跳转主界面闪现；剪贴板读取 200ms×3 重试；识别结果回调驱动迷你面板
- **IG Cookie 注入方法（固化）**：`adb root` → 写 `/data/data/com.clipdown.app/shared_prefs/clipdown_cookies.xml`（key=`instagram`，`k=v; k=v` 格式）→ chown u0_aXXX（uid=10204→u0_a204）→ force-stop 重启。**验证信号：WebView 抓取 title 从 "This content is unavailable • Instagram" 变为 "Instagram"**（页面 1037KB）
- 未解决：登录态下 WebView 渲染 reel 页 DOM 仍无媒体数据（阶段 10 遗留，真实帖子待测）；待用户提供真实 IG 帖子链接验证 GraphQL/embed 链路

## 阶段 13：IG 解析打通 + 图集隔离 + 风控定性（已完成 · 2026-09-28）
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
