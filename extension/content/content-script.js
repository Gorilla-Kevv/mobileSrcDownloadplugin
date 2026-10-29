/**
 * ClipDown 跨端助手 - In-page Content Script
 * 深度适配 X (Twitter)、Instagram、小红书与 YouTube 的媒体嗅探与页面下载注入
 */

(() => {
  const HOST = window.location.hostname.toLowerCase();

  // 1. 深度页面媒体嗅探器
  function sniffPageMedia() {
    // ── X (Twitter) ─────────────────────────────────────────────────────────
    if (HOST.includes("x.com") || HOST.includes("twitter.com")) {
      const match = window.location.pathname.match(/\/status\/(\d+)/);
      const tweetId = match ? match[1] : null;

      const videos = [];
      document.querySelectorAll("video").forEach(v => {
        if (v.src && !v.src.startsWith("blob:")) {
          videos.push(v.src);
        }
      });

      const images = [];
      document.querySelectorAll('article img[src*="pbs.twimg.com/media/"]').forEach(img => {
        let src = img.src;
        // 替换为最高无损画质 orig
        src = src.replace(/name=[a-zA-Z0-9_]+/, "name=orig");
        if (!src.includes("name=orig")) {
          src += (src.includes("?") ? "&" : "?") + "name=orig";
        }
        if (!images.includes(src)) images.push(src);
      });

      return {
        platform: "x",
        name: "X (Twitter)",
        id: tweetId,
        title: document.title.replace(/ \/ X$/, ""),
        url: window.location.href,
        videos,
        images
      };
    }

    // ── Instagram ───────────────────────────────────────────────────────────
    if (HOST.includes("instagram.com")) {
      const match = window.location.pathname.match(/\/(p|reel|reels)\/([a-zA-Z0-9_-]+)/);
      const shortcode = match ? match[2] : null;

      const videos = [];
      document.querySelectorAll("video").forEach(v => {
        if (v.src && v.src.startsWith("http")) {
          if (!videos.includes(v.src)) videos.push(v.src);
        }
      });

      const images = [];
      document.querySelectorAll("article img").forEach(img => {
        // 排除头像等微缩图 (150x150)
        if (img.src && !img.src.includes("s150x150") && !img.src.includes("s320x320")) {
          const candidate = getLargestFromSrcset(img.srcset) || img.src;
          if (candidate && !images.includes(candidate)) images.push(candidate);
        }
      });

      return {
        platform: "instagram",
        name: "Instagram",
        id: shortcode,
        title: document.title.replace(/ • Instagram photos and videos$/, ""),
        url: window.location.href,
        videos,
        images
      };
    }

    // ── 小红书 ─────────────────────────────────────────────────────────────
    if (HOST.includes("xiaohongshu.com")) {
      const match = window.location.pathname.match(/\/(explore|discovery\/item)\/([a-zA-Z0-9_-]+)/);
      const noteId = match ? match[2] : null;

      const videos = [];
      document.querySelectorAll("video").forEach(v => {
        if (v.src && v.src.startsWith("http") && !videos.includes(v.src)) {
          videos.push(v.src);
        }
      });

      const images = [];
      document.querySelectorAll('img[src*="sns-webpic"]').forEach(img => {
        let src = img.src;
        // 去除水印与尺寸裁剪后缀，获取原画
        src = src.replace(/![a-zA-Z0-9_-]+$/, "");
        if (!images.includes(src)) images.push(src);
      });

      return {
        platform: "xiaohongshu",
        name: "小红书",
        id: noteId,
        title: document.title.replace(/ - 小红书.*$/, ""),
        url: window.location.href,
        videos,
        images
      };
    }

    // ── YouTube ─────────────────────────────────────────────────────────────
    if (HOST.includes("youtube.com") || HOST.includes("youtu.be")) {
      let videoId = null;
      if (HOST.includes("youtu.be")) {
        videoId = window.location.pathname.substring(1);
      } else if (window.location.pathname.includes("/shorts/")) {
        videoId = window.location.pathname.split("/shorts/")[1].split("/")[0];
      } else {
        const params = new URLSearchParams(window.location.search);
        videoId = params.get("v");
      }

      return {
        platform: "youtube",
        name: "YouTube",
        id: videoId,
        title: document.title.replace(/ - YouTube$/, ""),
        url: window.location.href,
        thumbnail: videoId ? `https://i.ytimg.com/vi/${videoId}/maxresdefault.jpg` : null,
        videos: [],
        images: []
      };
    }

    return null;
  }

  function getLargestFromSrcset(srcset) {
    if (!srcset) return null;
    const parts = srcset.split(",").map(p => p.trim());
    let bestUrl = null;
    let maxW = 0;
    for (const part of parts) {
      const [u, w] = part.split(/\s+/);
      const width = parseInt(w, 10) || 0;
      if (width >= maxW) {
        maxW = width;
        bestUrl = u;
      }
    }
    return bestUrl;
  }

  // 监听来自 Popup 或 Background 的媒体嗅探请求
  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (msg.action === "EXTRACT_INPAGE_MEDIA") {
      const data = sniffPageMedia();
      sendResponse({ success: true, data });
    }
    return true;
  });

  // 2. 页面浮动深度解析与直接下载浮窗面板（右下角即时呈现清晰度，杜绝打扰弹窗）
  function injectFloatingWidget() {
    if (document.getElementById("clipdown-floating-widget")) return;

    const media = sniffPageMedia();
    if (!media) return;

    const widget = document.createElement("div");
    widget.id = "clipdown-floating-widget";
    widget.className = "clipdown-floating-widget";
    widget.innerHTML = `
      <div class="clipdown-float-btn" id="clipdown-float-trigger" title="ClipDown 媒体下载">
        <span class="clipdown-pulse"></span>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#C084FC" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 2v12M17 9l-5 5-5-5"/>
          <path d="M20 21H4"/>
        </svg>
      </div>
      <div class="clipdown-float-panel hidden" id="clipdown-float-panel">
        <!-- 动态由 loadAndRenderPanel 填充 -->
      </div>
    `;

    document.body.appendChild(widget);

    const trigger = widget.querySelector("#clipdown-float-trigger");
    const panel = widget.querySelector("#clipdown-float-panel");

    let currentMediaData = null;
    let isFetching = false;
    let downloadConfig = { downloadSubfolder: "ClipDown", groupByPlatform: true };

    // 获取下载目录配置
    chrome.runtime.sendMessage({ action: "GET_DOWNLOAD_CONFIG" }, (resp) => {
      if (resp && resp.success && resp.data) {
        downloadConfig = resp.data;
      }
    });

    // 触发点击展开/收起
    trigger.addEventListener("click", () => {
      const isHidden = panel.classList.contains("hidden");
      if (isHidden) {
        panel.classList.remove("hidden");
        loadAndRenderPanel();
      } else {
        panel.classList.add("hidden");
      }
    });

    // 点击外部关闭浮窗
    document.addEventListener("click", (e) => {
      if (!widget.contains(e.target)) {
        panel.classList.add("hidden");
      }
    });

    // 深度嗅探并渲染完整面板
    function loadAndRenderPanel(forceRefresh = false) {
      if (!forceRefresh && currentMediaData) {
        renderPanelContent(currentMediaData);
        return;
      }

      if (isFetching) return;
      isFetching = true;

      // 渲染加载中骨架
      panel.innerHTML = `
        <div class="clipdown-panel-header">
          <div class="clipdown-badge-row">
            <span class="clipdown-pill clipdown-pill-brand">${media.name || "解析中"}</span>
            <span class="clipdown-pill clipdown-pill-sub">正在深度探测</span>
          </div>
          <div class="clipdown-header-tools">
            <button class="clipdown-tool-btn" id="clipdown-btn-close">×</button>
          </div>
        </div>
        <div class="clipdown-loading-box">
          <div class="clipdown-spinner"></div>
          <div class="clipdown-loading-text">正在深度解析清晰度与媒体流...</div>
        </div>
      `;

      panel.querySelector("#clipdown-btn-close").addEventListener("click", (e) => {
        e.stopPropagation();
        panel.classList.add("hidden");
      });

      const inpageData = sniffPageMedia();
      chrome.runtime.sendMessage({
        action: "RESOLVE_MEDIA",
        url: window.location.href,
        inpageData
      }, (resp) => {
        isFetching = false;
        if (resp && resp.success && resp.data) {
          currentMediaData = resp.data;
          renderPanelContent(currentMediaData);
        } else if (inpageData) {
          currentMediaData = inpageData;
          renderPanelContent(currentMediaData);
        } else {
          renderEmptyContent();
        }
      });
    }

    // 渲染面板内容
    function renderPanelContent(data) {
      const platformName = data.platformName || data.name || "媒体下载";
      const title = (data.title || "ClipDown 媒体").trim();
      const cover = data.cover || data.thumbnail || (data.images && data.images[0]) || "";
      const items = data.items || [];
      const imageCount = items.filter(x => x.type === "image").length;
      const hasVideo = items.some(x => x.type === "video");

      let typeBadge = "在线媒体";
      if (hasVideo && imageCount > 0) typeBadge = `视频 + ${imageCount}图`;
      else if (hasVideo) typeBadge = "🎬 视频媒体";
      else if (imageCount > 0) typeBadge = `🖼️ ${imageCount}张原图`;

      const subfolder = downloadConfig.downloadSubfolder ? `Downloads/${downloadConfig.downloadSubfolder}` : "Downloads";

      panel.innerHTML = `
        <div class="clipdown-panel-header">
          <div class="clipdown-badge-row">
            <span class="clipdown-pill clipdown-pill-brand">${platformName}</span>
            <span class="clipdown-pill clipdown-pill-sub">${typeBadge}</span>
          </div>
          <div class="clipdown-header-tools">
            <button class="clipdown-tool-btn" id="clipdown-btn-refresh" title="重新嗅探">🔄</button>
            <button class="clipdown-tool-btn" id="clipdown-btn-close" title="关闭">×</button>
          </div>
        </div>

        <div class="clipdown-media-row">
          ${cover ? `<img src="${cover}" class="clipdown-media-cover" alt="Cover" />` : ""}
          <div class="clipdown-media-title" title="${title}">${title}</div>
        </div>

        <div class="clipdown-streams-list" id="clipdown-streams-list">
          ${items.length > 0 ? items.map((item, idx) => `
            <div class="clipdown-stream-item">
              <span class="clipdown-stream-label" title="${item.label}">${item.label}</span>
              <button class="clipdown-dl-btn" data-idx="${idx}">💻 下载</button>
            </div>
          `).join("") : `
            <div class="clipdown-empty-text">未直接探测到音视频直链，支持推送到手机下载</div>
          `}
        </div>

        <div class="clipdown-panel-actions">
          ${imageCount > 1 ? `
            <button class="clipdown-action-btn clipdown-btn-batch" id="clipdown-batch-btn">
              📦 一键下载全部 ${imageCount} 张原图
            </button>
          ` : ""}
          <button class="clipdown-action-btn clipdown-btn-mobile" id="clipdown-mobile-btn">
            🚀 推送到手机下载
          </button>
        </div>

        <div class="clipdown-panel-footer">
          <span class="clipdown-footer-path">📂 存至: <strong>${subfolder}</strong></span>
        </div>
      `;

      // 绑定关闭
      panel.querySelector("#clipdown-btn-close").addEventListener("click", (e) => {
        e.stopPropagation();
        panel.classList.add("hidden");
      });

      // 绑定刷新
      panel.querySelector("#clipdown-btn-refresh").addEventListener("click", (e) => {
        e.stopPropagation();
        loadAndRenderPanel(true);
      });

      // 绑定各清晰度/分项下载按钮（完全不跳 alert / toast，就地静默变绿反馈）
      panel.querySelectorAll(".clipdown-dl-btn").forEach(btn => {
        btn.addEventListener("click", (e) => {
          e.stopPropagation();
          const idx = parseInt(btn.dataset.idx, 10);
          const item = items[idx];
          if (!item) return;

          const rawTitle = title.substring(0, 40).replace(/[\\/:*?"<>|]/g, "_");
          const filename = `${rawTitle}.${item.ext || "mp4"}`;

          // 视觉反馈：变绿已开始，绝不跳任何打扰弹窗
          const origText = btn.textContent;
          btn.textContent = "✓ 已开始";
          btn.classList.add("clipdown-btn-success");

          chrome.runtime.sendMessage({
            action: "DOWNLOAD_MEDIA_PC",
            url: item.url,
            filename,
            platform: data.platform
          });

          setTimeout(() => {
            btn.textContent = origText;
            btn.classList.remove("clipdown-btn-success");
          }, 1800);
        });
      });

      // 批量图集下载按钮
      const batchBtn = panel.querySelector("#clipdown-batch-btn");
      if (batchBtn) {
        batchBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          const images = items.filter(x => x.type === "image");
          if (images.length === 0) return;

          const origText = batchBtn.textContent;
          batchBtn.textContent = "⏳ 正在启动批量下载...";
          batchBtn.classList.add("clipdown-btn-success");

          chrome.runtime.sendMessage({
            action: "DOWNLOAD_BATCH_PC",
            items: images,
            baseTitle: title.substring(0, 40).replace(/[\\/:*?"<>|]/g, "_"),
            platform: data.platform
          }, (res) => {
            batchBtn.textContent = `✓ 全部 ${images.length} 张原图已开始下载`;
            setTimeout(() => {
              batchBtn.textContent = origText;
              batchBtn.classList.remove("clipdown-btn-success");
            }, 2000);
          });
        });
      }

      // 推送到手机按钮
      const mobileBtn = panel.querySelector("#clipdown-mobile-btn");
      if (mobileBtn) {
        mobileBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          const origText = mobileBtn.textContent;
          mobileBtn.textContent = "正在推送...";

          chrome.runtime.sendMessage({
            action: "ENQUEUE_URL",
            url: window.location.href
          }, (res) => {
            if (res && res.success) {
              mobileBtn.textContent = "✓ 已推送到手机！";
              mobileBtn.classList.add("clipdown-btn-success");
            } else {
              mobileBtn.textContent = "❌ 推送失败(请查配对)";
            }
            setTimeout(() => {
              mobileBtn.textContent = origText;
              mobileBtn.classList.remove("clipdown-btn-success");
            }, 2000);
          });
        });
      }
    }

    function renderEmptyContent() {
      panel.innerHTML = `
        <div class="clipdown-panel-header">
          <div class="clipdown-badge-row">
            <span class="clipdown-pill clipdown-pill-brand">${media.name || "当前页"}</span>
            <span class="clipdown-pill clipdown-pill-sub">通用网页</span>
          </div>
          <div class="clipdown-header-tools">
            <button class="clipdown-tool-btn" id="clipdown-btn-close">×</button>
          </div>
        </div>
        <div class="clipdown-empty-text">未在当前页面嗅探到直接下载流，可点击下方按钮推送到手机端深度解析</div>
        <div class="clipdown-panel-actions">
          <button class="clipdown-action-btn clipdown-btn-mobile" id="clipdown-mobile-btn">
            🚀 推送到手机下载
          </button>
        </div>
      `;

      panel.querySelector("#clipdown-btn-close").addEventListener("click", (e) => {
        e.stopPropagation();
        panel.classList.add("hidden");
      });

      const mobileBtn = panel.querySelector("#clipdown-mobile-btn");
      if (mobileBtn) {
        mobileBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          chrome.runtime.sendMessage({
            action: "ENQUEUE_URL",
            url: window.location.href
          });
          mobileBtn.textContent = "✓ 已推送到手机！";
          mobileBtn.classList.add("clipdown-btn-success");
          setTimeout(() => {
            mobileBtn.textContent = "🚀 推送到手机下载";
            mobileBtn.classList.remove("clipdown-btn-success");
          }, 2000);
        });
      }
    }
  }

  // 延迟注入防白屏干扰
  setTimeout(injectFloatingWidget, 1500);
})();
