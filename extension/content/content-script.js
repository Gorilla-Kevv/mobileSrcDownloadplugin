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

  // 2. 页面浮动快捷下载按钮注入（优雅不遮挡，符合暗黑紫夜美学）
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
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#A855F7" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 2v12M17 9l-5 5-5-5"/>
          <path d="M20 21H4"/>
        </svg>
      </div>
      <div class="clipdown-float-menu hidden" id="clipdown-float-menu">
        <div class="clipdown-menu-header">
          <span class="clipdown-menu-title">${media.name} 快捷下载</span>
          <span class="clipdown-menu-close" id="clipdown-menu-close">×</span>
        </div>
        <div class="clipdown-menu-actions">
          <button class="clipdown-menu-btn" id="clipdown-action-pc-download">
            💻 下载到电脑
          </button>
          <button class="clipdown-menu-btn clipdown-btn-secondary" id="clipdown-action-mobile-enqueue">
            🚀 推送到手机
          </button>
        </div>
      </div>
    `;

    document.body.appendChild(widget);

    const trigger = widget.querySelector("#clipdown-float-trigger");
    const menu = widget.querySelector("#clipdown-float-menu");
    const closeBtn = widget.querySelector("#clipdown-menu-close");

    trigger.addEventListener("click", () => {
      menu.classList.toggle("hidden");
    });

    closeBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      menu.classList.add("hidden");
    });

    // 电脑下载
    widget.querySelector("#clipdown-action-pc-download").addEventListener("click", () => {
      chrome.runtime.sendMessage({
        action: "DOWNLOAD_CURRENT_TAB_PC"
      }, (res) => {
        if (res && res.success) {
          const folder = res.data?.folder ? `Downloads/${res.data.folder}` : "Downloads";
          triggerToast(`✅ 已启动电脑端下载 (保存至 ${folder})`);
        } else {
          triggerToast("⚠️ 请点击浏览器右上角 ClipDown 助手查看清晰度选项");
        }
      });
      menu.classList.add("hidden");
    });

    // 推送手机
    widget.querySelector("#clipdown-action-mobile-enqueue").addEventListener("click", () => {
      chrome.runtime.sendMessage({
        action: "ENQUEUE_URL",
        url: window.location.href
      }, (res) => {
        if (res && res.success) {
          triggerToast("🚀 已推送到手机下载中枢！");
        } else {
          triggerToast("❌ 推送失败，请检查手机局域网配对");
        }
      });
      menu.classList.add("hidden");
    });
  }

  function triggerToast(text) {
    const t = document.createElement("div");
    t.className = "clipdown-toast";
    t.textContent = text;
    document.body.appendChild(t);
    setTimeout(() => {
      t.classList.add("fade-out");
      setTimeout(() => t.remove(), 400);
    }, 2500);
  }

  // 延迟注入防白屏干扰
  setTimeout(injectFloatingWidget, 1500);
})();
