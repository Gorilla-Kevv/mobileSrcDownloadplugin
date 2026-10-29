/**
 * ClipDown 跨端助手 - In-page Content Script
 * 深度适配 X (Twitter)、Instagram、小红书与 YouTube 的媒体嗅探与页面下载注入
 * 核心创新：针对 Instagram 多图/信息流网页，采用「智能点赞监控 + 待下载清单 + 首图勾选下载 + 历史去重」架构
 */

(() => {
  const HOST = window.location.hostname.toLowerCase();
  const IS_INSTAGRAM = HOST.includes("instagram.com");

  // ── 通用工具函数 ─────────────────────────────────────────────────────────────
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

  // ── Instagram 单帖独立解析器（仅锁定当前帖子容器，杜绝全页乱抓）────────────
  function extractMediaFromInstagramArticle(article) {
    if (!article) return null;

    // 1. 获取 shortcode 与唯一标识
    let shortcode = null;
    const link = article.querySelector('a[href*="/p/"], a[href*="/reel/"]');
    if (link) {
      const m = (link.getAttribute("href") || "").match(/\/(p|reel|reels)\/([a-zA-Z0-9_-]+)/);
      if (m) shortcode = m[2];
    }
    if (!shortcode) {
      const m = window.location.pathname.match(/\/(p|reel|reels)\/([a-zA-Z0-9_-]+)/);
      if (m) shortcode = m[2];
    }
    if (!shortcode) {
      const anyImg = article.querySelector("img");
      const key = anyImg ? anyImg.src : (article.textContent || String(Math.random()));
      let h = 0;
      for (let i = 0; i < key.length; i++) {
        h = (Math.imul(31, h) + key.charCodeAt(i)) | 0;
      }
      shortcode = "ig_" + Math.abs(h).toString(36);
    }

    // 2. 作者提取
    let author = "";
    const authorEl = article.querySelector('header a, a[role="link"], span[dir="auto"] a');
    if (authorEl && authorEl.textContent) {
      author = authorEl.textContent.trim().replace(/^@/, "");
    }

    // 3. 配文提取
    let caption = "";
    const captionEl = article.querySelector('h1, div[data-testid="post-comment-root"] span, span[dir="auto"]');
    if (captionEl && captionEl.textContent) {
      caption = captionEl.textContent.trim().substring(0, 40);
    }
    const title = (author ? `@${author}: ` : "") + (caption || `Instagram 帖子 (${shortcode})`);

    // 4. 视频提取
    const videos = [];
    article.querySelectorAll("video").forEach(v => {
      if (v.src && v.src.startsWith("http") && !videos.includes(v.src)) {
        videos.push(v.src);
      }
    });

    // 5. 图片与首图缩略图提取（排除头像）
    const images = [];
    let cover = null;
    article.querySelectorAll("img").forEach(img => {
      const src = img.src || "";
      if (src && !src.includes("s150x150") && !src.includes("s320x320") && !img.closest("header")) {
        const candidate = getLargestFromSrcset(img.srcset) || src;
        if (candidate && !images.includes(candidate)) {
          images.push(candidate);
          if (!cover) cover = candidate;
        }
      }
    });

    if (videos.length === 0 && images.length === 0) return null;

    const items = [];
    if (videos.length > 0) {
      videos.forEach((v, idx) => {
        items.push({
          url: v,
          type: "video",
          label: `Reel 视频 ${idx + 1} (MP4)`,
          ext: "mp4"
        });
      });
    } else {
      images.forEach((img, idx) => {
        items.push({
          url: img,
          type: "image",
          label: `无水印原图 ${idx + 1}`,
          ext: "jpg"
        });
      });
    }

    return {
      shortcode,
      url: `https://www.instagram.com/p/${shortcode}/`,
      title,
      author: author || "Instagram",
      cover: cover || (videos.length > 0 ? videos[0] : ""),
      items,
      likedAt: Date.now()
    };
  }

  // ── 通用全页媒体嗅探器（供通用页面与非 IG 模式使用）────────────────────────
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

    // ── Instagram 单帖常规兜底 ───────────────────────────────────────────────
    if (IS_INSTAGRAM) {
      const article = document.querySelector("article");
      const post = extractMediaFromInstagramArticle(article);
      if (post) {
        return {
          platform: "instagram",
          name: "Instagram",
          id: post.shortcode,
          title: post.title,
          url: post.url,
          cover: post.cover,
          items: post.items,
          videos: post.items.filter(x => x.type === "video").map(x => x.url),
          images: post.items.filter(x => x.type === "image").map(x => x.url)
        };
      }
      return {
        platform: "instagram",
        name: "Instagram",
        id: null,
        title: document.title,
        url: window.location.href,
        videos: [],
        images: []
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

  // 监听来自扩展 Popup 的消息
  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (msg.action === "EXTRACT_INPAGE_MEDIA") {
      const data = sniffPageMedia();
      sendResponse({ success: true, data });
    }
    return true;
  });

  // ── 主逻辑：右下角悬浮挂件与点赞监听体系 ──────────────────────────────────
  function injectFloatingWidget() {
    if (document.getElementById("clipdown-floating-widget")) return;

    const widget = document.createElement("div");
    widget.id = "clipdown-floating-widget";
    widget.className = "clipdown-floating-widget";
    widget.innerHTML = `
      <div class="clipdown-float-btn" id="clipdown-float-trigger" title="ClipDown 媒体下载">
        <span class="clipdown-pulse"></span>
        <span class="clipdown-badge hidden" id="clipdown-badge">❤️ 0</span>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#C084FC" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 2v12M17 9l-5 5-5-5"/>
          <path d="M20 21H4"/>
        </svg>
      </div>
      <div class="clipdown-float-panel hidden" id="clipdown-float-panel">
        <!-- 动态渲染 -->
      </div>
    `;

    document.body.appendChild(widget);

    const trigger = widget.querySelector("#clipdown-float-trigger");
    const panel = widget.querySelector("#clipdown-float-panel");
    const badge = widget.querySelector("#clipdown-badge");

    let currentMode = IS_INSTAGRAM ? "instagram_liked" : "single_media";
    let isFetching = false;
    let downloadConfig = { downloadSubfolder: "ClipDown", groupByPlatform: true };

    // 获取下载目录设置
    chrome.runtime.sendMessage({ action: "GET_DOWNLOAD_CONFIG" }, (resp) => {
      if (resp && resp.success && resp.data) {
        downloadConfig = resp.data;
      }
    });

    // 更新角标状态
    async function updateBadge() {
      if (!IS_INSTAGRAM) return;
      const { ins_liked_posts = [], ins_downloaded_posts = [] } = await chrome.storage.local.get([
        "ins_liked_posts",
        "ins_downloaded_posts"
      ]);
      const pendingCount = ins_liked_posts.filter(p => !ins_downloaded_posts.includes(p.shortcode)).length;
      if (pendingCount > 0) {
        badge.textContent = `❤️ ${pendingCount}`;
        badge.classList.remove("hidden");
      } else {
        badge.classList.add("hidden");
      }
    }
    updateBadge();

    // 展开 / 收起悬浮面板
    trigger.addEventListener("click", () => {
      const isHidden = panel.classList.contains("hidden");
      if (isHidden) {
        panel.classList.remove("hidden");
        renderCurrentPanel();
      } else {
        panel.classList.add("hidden");
      }
    });

    // 点击外部自动收起
    document.addEventListener("click", (e) => {
      if (!widget.contains(e.target)) {
        panel.classList.add("hidden");
      }
    });

    function renderCurrentPanel() {
      if (IS_INSTAGRAM && currentMode === "instagram_liked") {
        renderInstagramLikedPanel();
      } else {
        renderSingleMediaPanel();
      }
    }

    // ── 核心专享：Instagram 已点赞待下载帖子清单（缩略图、默认全选、历史去重）─
    async function renderInstagramLikedPanel() {
      const { ins_liked_posts = [], ins_downloaded_posts = [] } = await chrome.storage.local.get([
        "ins_liked_posts",
        "ins_downloaded_posts"
      ]);

      const subfolder = downloadConfig.downloadSubfolder ? `Downloads/${downloadConfig.downloadSubfolder}/Instagram` : "Downloads/Instagram";
      const pendingPosts = ins_liked_posts.filter(p => !ins_downloaded_posts.includes(p.shortcode));
      const pendingCount = pendingPosts.length;

      panel.innerHTML = `
        <div class="clipdown-panel-header">
          <div class="clipdown-badge-row">
            <span class="clipdown-pill clipdown-pill-brand">Instagram</span>
            <span class="clipdown-pill clipdown-pill-sub">❤️ 点赞待下载清单</span>
          </div>
          <div class="clipdown-header-tools">
            <button class="clipdown-tool-btn" id="clipdown-switch-mode" title="切换到单帖探测模式">🔍</button>
            <button class="clipdown-tool-btn" id="clipdown-btn-close">×</button>
          </div>
        </div>

        <div class="clipdown-toolbar-row">
          <span style="color:#CBD5E1; font-weight:600;">共 ${ins_liked_posts.length} 条已点赞 · <strong style="color:#C084FC;">${pendingCount} 待下载</strong></span>
          <div>
            <button class="clipdown-text-btn" id="btn-select-all">全选</button>
            <button class="clipdown-text-btn" id="btn-unselect-all">取消全选</button>
          </div>
        </div>

        <div class="clipdown-liked-list" id="clipdown-liked-list">
          ${ins_liked_posts.length > 0 ? ins_liked_posts.map((post) => {
            const isDownloaded = ins_downloaded_posts.includes(post.shortcode);
            const mediaTypeStr = post.items.some(x => x.type === "video") ? "🎬 视频" : `🖼️ ${post.items.length}张原图`;
            return `
              <div class="clipdown-liked-item ${isDownloaded ? "is-downloaded" : ""}" data-shortcode="${post.shortcode}">
                <input type="checkbox" class="clipdown-post-check" data-shortcode="${post.shortcode}" ${!isDownloaded ? "checked" : ""} />
                <img src="${post.cover || '../icons/icon48.png'}" class="clipdown-post-thumb" title="点击预览" />
                <div class="clipdown-post-info">
                  <div class="clipdown-post-author">@${post.author}</div>
                  <div class="clipdown-post-title" title="${post.title}">${post.title}</div>
                  <div class="clipdown-post-meta">
                    <span>${mediaTypeStr}</span>
                    ${isDownloaded ? `<span class="clipdown-tag-downloaded">已下载</span>` : `<span class="clipdown-tag-pending">待下载</span>`}
                  </div>
                </div>
              </div>
            `;
          }).join("") : `
            <div class="clipdown-empty-text">
              <div style="font-size: 24px; margin-bottom: 6px;">❤️</div>
              <strong style="color: #FFFFFF; font-size: 13px;">智能点赞监控中</strong><br/>
              在 Instagram 刷动态时给帖子点赞（点击爱心 ❤️ 或双击图片），点赞的帖子将自动捕获并在此显示首图，一键下载！
            </div>
          `}
        </div>

        <div class="clipdown-panel-actions">
          <button class="clipdown-action-btn clipdown-btn-batch" id="btn-download-selected-posts">
            💻 一键下载选中的点赞帖子
          </button>
          <div style="display:flex; justify-content:space-between; align-items:center;">
            <button class="clipdown-text-btn" id="btn-clear-downloaded" style="font-size:11px; color:#94A3B8;">清空已下载记录</button>
            <button class="clipdown-text-btn" id="btn-clear-all-liked" style="font-size:11px; color:#94A3B8;">清空全部点赞列表</button>
          </div>
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

      // 切换到通用单帖嗅探
      panel.querySelector("#clipdown-switch-mode").addEventListener("click", (e) => {
        e.stopPropagation();
        currentMode = "single_media";
        renderSingleMediaPanel();
      });

      // 全选 / 取消全选（默认未下载的全选，用户可随意点击取消）
      const checkBoxes = panel.querySelectorAll(".clipdown-post-check");
      const btnSelectAll = panel.querySelector("#btn-select-all");
      const btnUnselectAll = panel.querySelector("#btn-unselect-all");

      if (btnSelectAll) {
        btnSelectAll.addEventListener("click", (e) => {
          e.stopPropagation();
          checkBoxes.forEach(cb => cb.checked = true);
          updateDownloadBtnCount();
        });
      }
      if (btnUnselectAll) {
        btnUnselectAll.addEventListener("click", (e) => {
          e.stopPropagation();
          checkBoxes.forEach(cb => cb.checked = false);
          updateDownloadBtnCount();
        });
      }

      // 监听单选变动更新按钮数量
      const btnDownloadSelected = panel.querySelector("#btn-download-selected-posts");
      function updateDownloadBtnCount() {
        const checkedCount = Array.from(checkBoxes).filter(cb => cb.checked).length;
        if (btnDownloadSelected) {
          btnDownloadSelected.textContent = checkedCount > 0
            ? `💻 一键下载选中的 ${checkedCount} 条帖子`
            : "请先勾选要下载的帖子";
          btnDownloadSelected.disabled = checkedCount === 0;
        }
      }
      checkBoxes.forEach(cb => {
        cb.addEventListener("change", updateDownloadBtnCount);
      });
      updateDownloadBtnCount();

      // 执行下载选中的点赞帖子（无感静默，不跳 alert 弹窗，记录下载状态）
      if (btnDownloadSelected) {
        btnDownloadSelected.addEventListener("click", async (e) => {
          e.stopPropagation();
          const selectedCodes = Array.from(checkBoxes).filter(cb => cb.checked).map(cb => cb.dataset.shortcode);
          if (selectedCodes.length === 0) return;

          const postsToDownload = ins_liked_posts.filter(p => selectedCodes.includes(p.shortcode));
          if (postsToDownload.length === 0) return;

          btnDownloadSelected.disabled = true;
          btnDownloadSelected.textContent = `⏳ 正在启动 ${postsToDownload.length} 条帖子下载...`;
          btnDownloadSelected.classList.add("clipdown-btn-success");

          // 循环触发下载
          for (const post of postsToDownload) {
            const rawTitle = (post.title || "Instagram_Post").substring(0, 40).replace(/[\\/:*?"<>|]/g, "_");
            const images = post.items.filter(x => x.type === "image");
            const videos = post.items.filter(x => x.type === "video");

            if (videos.length > 0) {
              // 视频帖
              chrome.runtime.sendMessage({
                action: "DOWNLOAD_MEDIA_PC",
                url: videos[0].url,
                filename: `${rawTitle}.mp4`,
                platform: "instagram"
              });
            } else if (images.length > 1) {
              // 图集帖
              chrome.runtime.sendMessage({
                action: "DOWNLOAD_BATCH_PC",
                items: images,
                baseTitle: rawTitle,
                platform: "instagram"
              });
            } else if (images.length === 1) {
              // 单图帖
              chrome.runtime.sendMessage({
                action: "DOWNLOAD_MEDIA_PC",
                url: images[0].url,
                filename: `${rawTitle}.jpg`,
                platform: "instagram"
              });
            }
          }

          // 记录已下载（持久化）
          const updatedDownloaded = Array.from(new Set([...ins_downloaded_posts, ...selectedCodes]));
          await chrome.storage.local.set({ ins_downloaded_posts: updatedDownloaded });

          btnDownloadSelected.textContent = `✓ 已开始下载 ${postsToDownload.length} 条点赞帖子`;
          updateBadge();

          setTimeout(() => {
            renderInstagramLikedPanel();
          }, 1800);
        });
      }

      // 清空已下载记录
      const btnClearDownloaded = panel.querySelector("#btn-clear-downloaded");
      if (btnClearDownloaded) {
        btnClearDownloaded.addEventListener("click", async (e) => {
          e.stopPropagation();
          const remainingLiked = ins_liked_posts.filter(p => !ins_downloaded_posts.includes(p.shortcode));
          await chrome.storage.local.set({ ins_liked_posts: remainingLiked });
          renderInstagramLikedPanel();
          updateBadge();
        });
      }

      // 清空全部点赞列表
      const btnClearAllLiked = panel.querySelector("#btn-clear-all-liked");
      if (btnClearAllLiked) {
        btnClearAllLiked.addEventListener("click", async (e) => {
          e.stopPropagation();
          await chrome.storage.local.set({ ins_liked_posts: [] });
          renderInstagramLikedPanel();
          updateBadge();
        });
      }
    }

    // ── 通用单帖解析面板（X、YouTube、小红书与 IG 备选模式）────────────────────
    function renderSingleMediaPanel() {
      panel.innerHTML = `
        <div class="clipdown-panel-header">
          <div class="clipdown-badge-row">
            <span class="clipdown-pill clipdown-pill-brand">网页探测</span>
            <span class="clipdown-pill clipdown-pill-sub">单帖深度解析</span>
          </div>
          <div class="clipdown-header-tools">
            ${IS_INSTAGRAM ? `<button class="clipdown-tool-btn" id="clipdown-switch-to-liked" title="返回点赞清单">❤️</button>` : ""}
            <button class="clipdown-tool-btn" id="clipdown-btn-refresh" title="重新嗅探">🔄</button>
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

      if (IS_INSTAGRAM) {
        panel.querySelector("#clipdown-switch-to-liked")?.addEventListener("click", (e) => {
          e.stopPropagation();
          currentMode = "instagram_liked";
          renderInstagramLikedPanel();
        });
      }

      const inpageData = sniffPageMedia();
      chrome.runtime.sendMessage({
        action: "RESOLVE_MEDIA",
        url: window.location.href,
        inpageData
      }, (resp) => {
        if (resp && resp.success && resp.data) {
          renderResolvedSingleMedia(resp.data);
        } else if (inpageData) {
          renderResolvedSingleMedia(inpageData);
        } else {
          renderEmptySingleMedia();
        }
      });
    }

    function renderResolvedSingleMedia(data) {
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
            ${IS_INSTAGRAM ? `<button class="clipdown-tool-btn" id="clipdown-switch-to-liked" title="返回点赞清单">❤️</button>` : ""}
            <button class="clipdown-tool-btn" id="clipdown-btn-refresh" title="重新嗅探">🔄</button>
            <button class="clipdown-tool-btn" id="clipdown-btn-close">×</button>
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

      panel.querySelector("#clipdown-btn-close").addEventListener("click", (e) => {
        e.stopPropagation();
        panel.classList.add("hidden");
      });
      panel.querySelector("#clipdown-btn-refresh").addEventListener("click", (e) => {
        e.stopPropagation();
        renderSingleMediaPanel();
      });
      if (IS_INSTAGRAM) {
        panel.querySelector("#clipdown-switch-to-liked")?.addEventListener("click", (e) => {
          e.stopPropagation();
          currentMode = "instagram_liked";
          renderInstagramLikedPanel();
        });
      }

      // 单项下载
      panel.querySelectorAll(".clipdown-dl-btn").forEach(btn => {
        btn.addEventListener("click", (e) => {
          e.stopPropagation();
          const idx = parseInt(btn.dataset.idx, 10);
          const item = items[idx];
          if (!item) return;

          const rawTitle = title.substring(0, 40).replace(/[\\/:*?"<>|]/g, "_");
          const filename = `${rawTitle}.${item.ext || "mp4"}`;

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

      // 批量原图
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
          }, () => {
            batchBtn.textContent = `✓ 全部 ${images.length} 张原图已开始下载`;
            setTimeout(() => {
              batchBtn.textContent = origText;
              batchBtn.classList.remove("clipdown-btn-success");
            }, 2000);
          });
        });
      }

      // 推送到手机
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

    function renderEmptySingleMedia() {
      panel.innerHTML = `
        <div class="clipdown-panel-header">
          <div class="clipdown-badge-row">
            <span class="clipdown-pill clipdown-pill-brand">当前页面</span>
            <span class="clipdown-pill clipdown-pill-sub">通用网页</span>
          </div>
          <div class="clipdown-header-tools">
            ${IS_INSTAGRAM ? `<button class="clipdown-tool-btn" id="clipdown-switch-to-liked" title="返回点赞清单">❤️</button>` : ""}
            <button class="clipdown-tool-btn" id="clipdown-btn-close">×</button>
          </div>
        </div>
        <div class="clipdown-empty-text">未直接探测到下载流，可点击下方按钮推送到手机端深度解析</div>
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
      if (IS_INSTAGRAM) {
        panel.querySelector("#clipdown-switch-to-liked")?.addEventListener("click", (e) => {
          e.stopPropagation();
          currentMode = "instagram_liked";
          renderInstagramLikedPanel();
        });
      }
      panel.querySelector("#clipdown-mobile-btn")?.addEventListener("click", (e) => {
        e.stopPropagation();
        chrome.runtime.sendMessage({ action: "ENQUEUE_URL", url: window.location.href });
      });
    }

    // ── 注册 Instagram 点赞监听器 ─────────────────────────────────────────────
    if (IS_INSTAGRAM) {
      async function onPostLiked(articleEl) {
        const postData = extractMediaFromInstagramArticle(articleEl);
        if (!postData || !postData.shortcode) return;

        const { ins_liked_posts = [], ins_downloaded_posts = [] } = await chrome.storage.local.get([
          "ins_liked_posts",
          "ins_downloaded_posts"
        ]);

        const existingIdx = ins_liked_posts.findIndex(p => p.shortcode === postData.shortcode);
        if (existingIdx >= 0) {
          ins_liked_posts[existingIdx] = postData;
        } else {
          ins_liked_posts.unshift(postData);
        }

        const pruned = ins_liked_posts.slice(0, 60);
        await chrome.storage.local.set({ ins_liked_posts: pruned });
        updateBadge();

        // 若面板正打开且处于点赞清单模式，实时刷新
        if (!panel.classList.contains("hidden") && currentMode === "instagram_liked") {
          renderInstagramLikedPanel();
        }
      }

      // 1. 点击捕获爱心按钮
      document.addEventListener("click", (e) => {
        const btn = e.target.closest('button, [role="button"], span');
        if (!btn) return;

        const svg = btn.querySelector('svg[aria-label="Like"], svg[aria-label="赞"], svg[aria-label="点赞"]') ||
                    (btn.matches('svg[aria-label="Like"], svg[aria-label="赞"]') ? btn : null);
        if (svg) {
          const article = btn.closest("article") || document.querySelector("article");
          if (article) {
            setTimeout(() => {
              onPostLiked(article);
            }, 300);
          }
        }
      }, true);

      // 2. 双击图片点赞与 MutationObserver 监听
      const observer = new MutationObserver((mutations) => {
        for (const m of mutations) {
          if (m.type === "attributes" && m.attributeName === "aria-label") {
            const el = m.target;
            const label = el.getAttribute("aria-label");
            if (label === "Unlike" || label === "取消赞" || label === "收回赞") {
              const article = el.closest("article") || document.querySelector("article");
              if (article) {
                onPostLiked(article);
              }
            }
          }
        }
      });

      observer.observe(document.body, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ["aria-label"]
      });
    }
  }

  // 延迟注入防白屏干扰
  setTimeout(injectFloatingWidget, 1500);
})();
