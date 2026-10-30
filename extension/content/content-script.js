/**
 * ClipDown 跨端助手 - In-page Content Script
 * 深度适配 X (Twitter)、Instagram、小红书与 YouTube 的媒体嗅探与页面下载注入
 * 核心创新：
 * 1. 方案 1（瀑布流卡片直接下载 - 最优解）：在图片/视频帖子右下角直接注入高质感下载胶囊按钮，点击只下载当前帖（视频/单图/多图图集秒级直接批量落盘）
 * 2. 方案 2（智能点赞监控与双端待下载清单）：全覆盖捕获点击、双击与 React 节点替换事件，实时统计角标，首图去重防漏，批量一键下载
 * 3. Instagram 多图图集深度解析：自动识别轮播组件、从页面内部 SSR 脚本或后台请求完整解析 edge_sidecar_to_children，彻底解决只能抓取单张问题
 * 4. 全页面无限瀑布流深度采集（博主主页/个人点赞页/媒体页）
 */

(() => {
  const HOST = window.location.hostname.toLowerCase();
  const IS_INSTAGRAM = HOST.includes("instagram.com");
  const IS_X = HOST.includes("x.com") || HOST.includes("twitter.com");
  const IS_XIAOHONGSHU = HOST.includes("xiaohongshu.com");
  const IS_YOUTUBE = HOST.includes("youtube.com") || HOST.includes("youtu.be");

  // ── 通用工具函数 ─────────────────────────────────────────────────────────────
  function escapeHtml(text) {
    if (!text) return "";
    return String(text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
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

  function isProfileOrLikesPage() {
    const path = window.location.pathname.toLowerCase();
    const href = window.location.href.toLowerCase();
    if (IS_X) {
      const parts = path.split("/").filter(Boolean);
      return parts.length >= 1 && !["home", "explore", "notifications", "messages", "search", "settings", "i"].includes(parts[0]);
    }
    if (IS_INSTAGRAM) {
      if (href.includes("interactions/likes") || href.includes("/saved/")) return true;
      const parts = path.split("/").filter(Boolean);
      return parts.length >= 1 && !["explore", "direct", "stories", "accounts"].includes(parts[0]);
    }
    if (IS_XIAOHONGSHU) {
      return path.includes("/user/profile/");
    }
    if (IS_YOUTUBE) {
      return path.includes("/@") || path.includes("/channel/") || path.includes("/c/") || path.includes("/playlist") || path.includes("/videos");
    }
    return false;
  }

  // ── Instagram 是否为轮播图集判断 ───────────────────────────────────────────
  function isInstagramArticleCarousel(article) {
    if (!article) return false;
    // 1. 点状指示器 (dots)
    if (article.querySelector('ul._acay, div._aamz, div._acaz, [aria-label*="slide" i], [aria-label*="幻灯片"]')) return true;
    // 2. 下一页翻页按钮
    if (article.querySelector('button[aria-label*="Next" i], button[aria-label*="下一个" i], button[aria-label*="Weiter" i]')) return true;
    // 3. 多张图片已挂载
    const imgs = article.querySelectorAll("img");
    let nonAvatarCount = 0;
    imgs.forEach(img => {
      if (img.src && !img.src.includes("s150x150") && !img.src.includes("s320x320") && !img.closest("header")) {
        nonAvatarCount++;
      }
    });
    if (nonAvatarCount > 1) return true;
    // 4. 多图图标
    if (article.querySelector('svg[aria-label*="Carousel" i], svg[aria-label*="图集" i], svg[aria-label*="多图" i]')) return true;
    return false;
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

    const isCarousel = isInstagramArticleCarousel(article);

    // 6. 核心优化：若是多图图集，优先从页面内嵌的 SSR script 中直接提取完整 edge_sidecar_to_children
    if (isCarousel && shortcode) {
      try {
        const scripts = document.querySelectorAll('script[type="application/json"]');
        for (const s of scripts) {
          const txt = s.textContent || "";
          if (txt.includes(shortcode) && (txt.includes("edge_sidecar_to_children") || txt.includes("carousel_media"))) {
            const sidecarMatch = txt.match(/"edge_sidecar_to_children"\s*:\s*\{"edges"\s*:\s*(\[.*?\])\s*\}/);
            if (sidecarMatch) {
              const edges = JSON.parse(sidecarMatch[1]);
              const sidecarItems = [];
              edges.forEach((edge, idx) => {
                const node = edge.node;
                if (node.is_video && node.video_url) {
                  sidecarItems.push({
                    url: node.video_url,
                    type: "video",
                    label: `Reel 视频 ${idx + 1} (MP4)`,
                    ext: "mp4"
                  });
                } else if (node.display_url) {
                  sidecarItems.push({
                    url: node.display_url,
                    type: "image",
                    label: `无水印原图 ${idx + 1}`,
                    ext: "jpg"
                  });
                }
              });
              if (sidecarItems.length > 0) {
                return {
                  platform: "instagram",
                  shortcode,
                  url: `https://www.instagram.com/p/${shortcode}/`,
                  title,
                  author: author || "Instagram",
                  cover: cover || sidecarItems[0].url,
                  items: sidecarItems,
                  isCarousel: true,
                  likedAt: Date.now()
                };
              }
            }
          }
        }
      } catch (_) {}
    }

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
      platform: "instagram",
      shortcode,
      url: `https://www.instagram.com/p/${shortcode}/`,
      title,
      author: author || "Instagram",
      cover: cover || (videos.length > 0 ? videos[0] : ""),
      items,
      isCarousel,
      likedAt: Date.now()
    };
  }

  // ── X (Twitter) 单推文独立解析器（仅锁定当前推文容器）────────────────────
  function extractMediaFromXTweet(tweetEl) {
    if (!tweetEl) return null;

    // 1. 获取 statusId (推文唯一数字 ID)
    let statusId = null;
    let tweetUrl = "";
    const statusLink = tweetEl.querySelector('a[href*="/status/"]');
    if (statusLink) {
      const href = statusLink.getAttribute("href") || "";
      const m = href.match(/\/status\/(\d+)/);
      if (m) {
        statusId = m[1];
        tweetUrl = href.startsWith("http") ? href : `https://x.com${href}`;
      }
    }
    if (!statusId) {
      const m = window.location.pathname.match(/\/status\/(\d+)/);
      if (m) {
        statusId = m[1];
        tweetUrl = window.location.href;
      }
    }
    if (!statusId) {
      const anyImg = tweetEl.querySelector("img");
      const key = anyImg ? anyImg.src : (tweetEl.textContent || String(Math.random()));
      let h = 0;
      for (let i = 0; i < key.length; i++) {
        h = (Math.imul(31, h) + key.charCodeAt(i)) | 0;
      }
      statusId = "x_" + Math.abs(h).toString(36);
      tweetUrl = window.location.href;
    }

    // 2. 作者提取
    let author = "";
    const userEl = tweetEl.querySelector('div[data-testid="User-Name"]');
    if (userEl) {
      const handleMatch = userEl.textContent.match(/@([a-zA-Z0-9_]+)/);
      if (handleMatch) {
        author = handleMatch[1];
      }
    }
    if (!author) {
      const match = tweetUrl.match(/x\.com\/([^/]+)\/status/);
      if (match) author = match[1];
    }

    // 3. 推文文本提取
    let tweetText = "";
    const textEl = tweetEl.querySelector('div[data-testid="tweetText"]');
    if (textEl) {
      tweetText = textEl.textContent.trim().substring(0, 50);
    }
    const title = (author ? `@${author}: ` : "") + (tweetText || `X 推文 (${statusId})`);

    // 4. 原图提取 (将 name 替换为 orig 保证最高画质)
    const images = [];
    let cover = null;
    tweetEl.querySelectorAll('img[src*="pbs.twimg.com/media/"]').forEach(img => {
      let src = img.src || "";
      src = src.replace(/name=[a-zA-Z0-9_]+/, "name=orig");
      if (!src.includes("name=orig")) {
        src += (src.includes("?") ? "&" : "?") + "name=orig";
      }
      if (!images.includes(src)) {
        images.push(src);
        if (!cover) cover = src;
      }
    });

    // 5. 视频提取
    const videos = [];
    tweetEl.querySelectorAll("video").forEach(v => {
      if (v.src && !v.src.startsWith("blob:") && !videos.includes(v.src)) {
        videos.push(v.src);
      }
      if (!cover && v.poster) {
        cover = v.poster;
      }
    });

    if (images.length === 0 && videos.length === 0) {
      const anyImg = tweetEl.querySelector('img[src*="twimg.com"]');
      if (anyImg && !cover) cover = anyImg.src;
    }

    const items = [];
    videos.forEach((v, idx) => {
      items.push({
        url: v,
        type: "video",
        label: `推文视频 ${idx + 1} (MP4)`,
        ext: "mp4"
      });
    });
    images.forEach((img, idx) => {
      items.push({
        url: img,
        type: "image",
        label: `最高清原图 ${idx + 1}`,
        ext: "jpg"
      });
    });

    return {
      platform: "x",
      shortcode: statusId,
      url: tweetUrl,
      title,
      author: author || "X_User",
      cover: cover || "",
      items,
      likedAt: Date.now()
    };
  }

  // ── 全页面作品嗅探引擎（X、Instagram、小红书无限瀑布流聚合）─────────────────
  function harvestCurrentPageItems() {
    const harvested = [];
    const seenCodes = new Set();

    if (IS_X) {
      document.querySelectorAll('article[data-testid="tweet"]').forEach(tweetEl => {
        const item = extractMediaFromXTweet(tweetEl);
        if (item && item.shortcode && !seenCodes.has(item.shortcode)) {
          seenCodes.add(item.shortcode);
          harvested.push(item);
        }
      });
    } else if (IS_INSTAGRAM) {
      document.querySelectorAll("article").forEach(art => {
        const item = extractMediaFromInstagramArticle(art);
        if (item && item.shortcode && !seenCodes.has(item.shortcode)) {
          seenCodes.add(item.shortcode);
          harvested.push(item);
        }
      });
      document.querySelectorAll('a[href*="/p/"], a[href*="/reel/"]').forEach(a => {
        const href = a.getAttribute("href") || "";
        const m = href.match(/\/(p|reel|reels)\/([a-zA-Z0-9_-]+)/);
        if (m) {
          const shortcode = m[2];
          if (!seenCodes.has(shortcode)) {
            seenCodes.add(shortcode);
            const img = a.querySelector("img");
            const cover = img ? (getLargestFromSrcset(img.srcset) || img.src) : "";
            const isVideo = !!a.querySelector('svg[aria-label="Reels"], svg[aria-label="Video"], svg[aria-label="Clip"]') || href.includes("/reel/");
            harvested.push({
              platform: "instagram",
              shortcode,
              url: `https://www.instagram.com/p/${shortcode}/`,
              title: (img && img.alt) ? img.alt.substring(0, 40) : `Instagram 作品 (${shortcode})`,
              author: window.location.pathname.split("/").filter(Boolean)[0] || "Instagram",
              cover,
              items: cover ? [{ url: cover, type: isVideo ? "video" : "image", label: isVideo ? "Reel 视频" : "无水印原图", ext: isVideo ? "mp4" : "jpg" }] : [],
              isVideo
            });
          }
        }
      });
    } else if (IS_XIAOHONGSHU) {
      document.querySelectorAll('section.note-item, .note-item, a[href*="/explore/"]').forEach(el => {
        const a = el.tagName === "A" ? el : el.querySelector('a[href*="/explore/"]');
        if (!a) return;
        const href = a.getAttribute("href") || "";
        const m = href.match(/\/explore\/([a-zA-Z0-9_-]+)/);
        if (m) {
          const noteId = m[1];
          if (!seenCodes.has(noteId)) {
            seenCodes.add(noteId);
            const img = el.querySelector("img");
            let cover = img ? img.src : "";
            if (cover) cover = cover.replace(/![a-zA-Z0-9_-]+$/, "");
            const titleEl = el.querySelector(".title, .footer .name, span");
            const title = titleEl ? titleEl.textContent.trim() : `小红书笔记 (${noteId})`;
            const isVideo = !!el.querySelector(".play-icon, svg");
            harvested.push({
              platform: "xiaohongshu",
              shortcode: noteId,
              url: `https://www.xiaohongshu.com/explore/${noteId}`,
              title,
              author: document.querySelector(".user-name, .author-name")?.textContent.trim() || "小红书博主",
              cover,
              items: cover ? [{ url: cover, type: isVideo ? "video" : "image", label: isVideo ? "视频封面" : "无水印大图", ext: isVideo ? "mp4" : "jpg" }] : [],
              isVideo
            });
          }
        }
      });
    } else if (IS_YOUTUBE) {
      document.querySelectorAll('ytd-rich-item-renderer, ytd-video-renderer, ytd-grid-video-renderer, ytd-compact-video-renderer, ytd-reel-item-renderer').forEach(el => {
        const link = el.querySelector('a#video-title-link, a#thumbnail, a[href*="/watch?v="], a[href*="/shorts/"]');
        if (!link) return;
        const href = link.getAttribute("href") || "";
        const videoId = href.includes("/shorts/")
          ? href.split("/shorts/")[1]?.split(/[?&]/)[0]
          : href.match(/[?&]v=([^&]+)/)?.[1];
        if (videoId && !seenCodes.has(videoId)) {
          seenCodes.add(videoId);
          const title = el.querySelector("#video-title, #video-title-link")?.textContent?.trim() || `YouTube 视频 (${videoId})`;
          const author = el.querySelector("#channel-name, ytd-channel-name, #owner")?.textContent?.trim() || "YouTube";
          const cover = `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;
          harvested.push({
            platform: "youtube",
            shortcode: videoId,
            url: href.startsWith("http") ? href : `https://www.youtube.com${href}`,
            title,
            author,
            cover,
            items: [
              { url: cover, type: "image", label: "视频封面 (JPG)", ext: "jpg" }
            ],
            isVideo: true
          });
        }
      });
    }

    return harvested;
  }

  // ── 通用全页单帖媒体嗅探器 ────────────────────────────────────────────────
  function sniffPageMedia() {
    if (IS_X) {
      const match = window.location.pathname.match(/\/status\/(\d+)/);
      const tweetId = match ? match[1] : null;

      const videos = [];
      document.querySelectorAll("video").forEach(v => {
        if (v.src && !v.src.startsWith("blob:") && !videos.includes(v.src)) {
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

    if (IS_XIAOHONGSHU) {
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

    if (IS_YOUTUBE) {
      let videoId = null;
      if (HOST.includes("youtu.be")) {
        videoId = window.location.pathname.substring(1).split(/[?&#]/)[0];
      } else if (window.location.pathname.includes("/shorts/")) {
        videoId = window.location.pathname.split("/shorts/")[1]?.split(/[?&#/]/)[0] || null;
      } else {
        const params = new URLSearchParams(window.location.search);
        videoId = params.get("v");
      }

      const ytTitle = document.querySelector("h1.ytd-watch-metadata, #title h1, h2.title, ytd-reel-video-renderer #title")?.textContent?.trim()
        || document.title.replace(/ - YouTube$/, "").trim();
      const ytAuthor = document.querySelector("#channel-name a, #owner #text a, ytd-channel-name a")?.textContent?.trim() || "YouTube";

      const videos = [];
      document.querySelectorAll("video").forEach(v => {
        if (v.src && !v.src.startsWith("blob:") && !videos.includes(v.src)) {
          videos.push(v.src);
        }
      });

      return {
        platform: "youtube",
        name: "YouTube",
        id: videoId,
        title: ytTitle || "YouTube 视频",
        author: ytAuthor,
        url: window.location.href,
        thumbnail: videoId ? `https://i.ytimg.com/vi/${videoId}/maxresdefault.jpg` : null,
        videos,
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

  // ── 核心创新 1：瀑布流帖子右下角直接注入下载按钮 (最优解方案 1) ───────────
  function injectCardDownloadButtons() {
    // 1. Instagram 瀑布流单帖
    if (IS_INSTAGRAM) {
      document.querySelectorAll('article:not([data-clipdown-injected])').forEach(article => {
        article.setAttribute("data-clipdown-injected", "true");

        // 定位媒体容器或第一层卡片块
        const mediaWrap = article.querySelector('div._aagv, div._aatk, div._aato, div._ab6-, div[role="presentation"], div[role="button"]') ||
                          article.querySelector('div:has(> img), div:has(> video)') ||
                          article;

        if (mediaWrap) {
          const compStyle = window.getComputedStyle(mediaWrap);
          if (compStyle.position === "static") {
            mediaWrap.style.position = "relative";
          }

          const isCarousel = isInstagramArticleCarousel(article);
          const hasVideo = !!article.querySelector("video");
          const labelText = isCarousel ? "📦 下载图集" : (hasVideo ? "🎬 下载视频" : "⬇️ 下载原图");

          const btn = document.createElement("div");
          btn.className = "clipdown-card-dl-btn";
          btn.title = "ClipDown 一键下载此帖子 (仅下载此项)";
          btn.innerHTML = `
            <span class="clipdown-card-dl-icon">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 2v13M17 10l-5 5-5-5"/>
                <path d="M20 21H4"/>
              </svg>
            </span>
            <span class="clipdown-card-dl-label">${labelText}</span>
          `;

          btn.addEventListener("click", async (e) => {
            e.stopPropagation();
            e.preventDefault();

            const origContent = btn.innerHTML;
            btn.classList.add("loading");
            btn.innerHTML = `<span class="clipdown-inline-spinner"></span> 正在解析...`;

            try {
              let postData = extractMediaFromInstagramArticle(article);
              if (!postData || !postData.shortcode) throw new Error("未能定位帖子标识");

              // 若检测为图集或仅抓到 1 张图，向后台请求完整 shortcode 解析（解决多图问题）
              if (isCarousel || (postData.items && postData.items.length <= 1)) {
                btn.innerHTML = `<span class="clipdown-inline-spinner"></span> 解析图集中...`;
                const fullRes = await new Promise(resolve => {
                  chrome.runtime.sendMessage({
                    action: "RESOLVE_INSTAGRAM_POST",
                    shortcode: postData.shortcode,
                    inpageItems: postData.items
                  }, resolve);
                });

                if (fullRes && fullRes.success && fullRes.data && fullRes.data.items && fullRes.data.items.length > 0) {
                  postData.items = fullRes.data.items;
                  if (fullRes.data.title) postData.title = fullRes.data.title;
                  if (fullRes.data.author) postData.author = fullRes.data.author;
                }
              }

              const rawTitle = (postData.title || "Instagram_Post").substring(0, 40).replace(/[\\/:*?"<>|]/g, "_");
              const images = (postData.items || []).filter(x => x.type === "image");
              const videos = (postData.items || []).filter(x => x.type === "video");

              if (videos.length > 0) {
                btn.innerHTML = `⏳ 正在下载视频...`;
                chrome.runtime.sendMessage({
                  action: "DOWNLOAD_MEDIA_PC",
                  url: videos[0].url,
                  filename: `${rawTitle}.mp4`,
                  platform: "instagram"
                });
                btn.classList.remove("loading");
                btn.classList.add("success");
                btn.innerHTML = `✓ 已下载`;
              } else if (images.length > 1) {
                btn.innerHTML = `⏳ 下载全部 ${images.length} 张原图...`;
                chrome.runtime.sendMessage({
                  action: "DOWNLOAD_BATCH_PC",
                  items: images,
                  baseTitle: rawTitle,
                  platform: "instagram"
                });
                btn.classList.remove("loading");
                btn.classList.add("success");
                btn.innerHTML = `✓ 已下载 (${images.length}图)`;
              } else if (images.length === 1) {
                btn.innerHTML = `⏳ 正在下载原图...`;
                chrome.runtime.sendMessage({
                  action: "DOWNLOAD_MEDIA_PC",
                  url: images[0].url,
                  filename: `${rawTitle}.jpg`,
                  platform: "instagram"
                });
                btn.classList.remove("loading");
                btn.classList.add("success");
                btn.innerHTML = `✓ 已下载`;
              }

              // 记录已下载去重
              const { ins_downloaded_posts = [] } = await chrome.storage.local.get(["ins_downloaded_posts"]);
              if (!ins_downloaded_posts.includes(postData.shortcode)) {
                ins_downloaded_posts.push(postData.shortcode);
                await chrome.storage.local.set({ ins_downloaded_posts });
                updateBadge();
              }
            } catch (err) {
              console.warn("Instagram inline download error:", err);
              btn.classList.remove("loading");
              btn.innerHTML = `❌ 下载失败`;
            }

            setTimeout(() => {
              btn.classList.remove("success", "loading");
              btn.innerHTML = origContent;
            }, 3000);
          });

          mediaWrap.appendChild(btn);
        }
      });
    }

    // 2. X (Twitter) 瀑布流单推文
    if (IS_X) {
      document.querySelectorAll('article[data-testid="tweet"]:not([data-clipdown-injected])').forEach(tweetEl => {
        tweetEl.setAttribute("data-clipdown-injected", "true");

        const mediaWrap = tweetEl.querySelector('[data-testid="tweetPhoto"], [data-testid="videoPlayer"], [data-testid="videoComponent"], div[aria-label*="Image" i]') ||
                          tweetEl.querySelector('div[role="group"]') ||
                          tweetEl;

        if (mediaWrap) {
          const compStyle = window.getComputedStyle(mediaWrap);
          if (compStyle.position === "static") {
            mediaWrap.style.position = "relative";
          }

          const hasVideo = !!tweetEl.querySelector("video");
          const labelText = hasVideo ? "🎬 下载视频" : "⬇️ 下载原图";

          const btn = document.createElement("div");
          btn.className = "clipdown-card-dl-btn";
          btn.title = "ClipDown 一键下载此推文媒体";
          btn.innerHTML = `
            <span class="clipdown-card-dl-icon">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 2v13M17 10l-5 5-5-5"/>
                <path d="M20 21H4"/>
              </svg>
            </span>
            <span class="clipdown-card-dl-label">${labelText}</span>
          `;

          btn.addEventListener("click", async (e) => {
            e.stopPropagation();
            e.preventDefault();

            const origContent = btn.innerHTML;
            btn.classList.add("loading");
            btn.innerHTML = `<span class="clipdown-inline-spinner"></span> 准备下载...`;

            try {
              const tweetData = extractMediaFromXTweet(tweetEl);
              if (!tweetData) throw new Error("未能定位推文");

              const rawTitle = (tweetData.title || "X_Tweet").substring(0, 40).replace(/[\\/:*?"<>|]/g, "_");
              const images = (tweetData.items || []).filter(x => x.type === "image");
              const videos = (tweetData.items || []).filter(x => x.type === "video");

              if (videos.length > 0) {
                btn.innerHTML = `⏳ 正在下载视频...`;
                chrome.runtime.sendMessage({
                  action: "DOWNLOAD_MEDIA_PC",
                  url: videos[0].url,
                  filename: `${rawTitle}.mp4`,
                  platform: "x"
                });
                btn.classList.remove("loading");
                btn.classList.add("success");
                btn.innerHTML = `✓ 已下载`;
              } else if (images.length > 1) {
                btn.innerHTML = `⏳ 下载全部 ${images.length} 张原图...`;
                chrome.runtime.sendMessage({
                  action: "DOWNLOAD_BATCH_PC",
                  items: images,
                  baseTitle: rawTitle,
                  platform: "x"
                });
                btn.classList.remove("loading");
                btn.classList.add("success");
                btn.innerHTML = `✓ 已下载 (${images.length}图)`;
              } else if (images.length === 1) {
                btn.innerHTML = `⏳ 正在下载最高清原图...`;
                chrome.runtime.sendMessage({
                  action: "DOWNLOAD_MEDIA_PC",
                  url: images[0].url,
                  filename: `${rawTitle}.jpg`,
                  platform: "x"
                });
                btn.classList.remove("loading");
                btn.classList.add("success");
                btn.innerHTML = `✓ 已下载`;
              } else if (tweetData.cover) {
                btn.innerHTML = `⏳ 正在下载...`;
                chrome.runtime.sendMessage({
                  action: "DOWNLOAD_MEDIA_PC",
                  url: tweetData.cover,
                  filename: `${rawTitle}.jpg`,
                  platform: "x"
                });
                btn.classList.remove("loading");
                btn.classList.add("success");
                btn.innerHTML = `✓ 已下载`;
              }

              const { x_downloaded_posts = [] } = await chrome.storage.local.get(["x_downloaded_posts"]);
              if (!x_downloaded_posts.includes(tweetData.shortcode)) {
                x_downloaded_posts.push(tweetData.shortcode);
                await chrome.storage.local.set({ x_downloaded_posts });
                updateBadge();
              }
            } catch (err) {
              btn.classList.remove("loading");
              btn.innerHTML = `❌ 下载失败`;
            }

            setTimeout(() => {
              btn.classList.remove("success", "loading");
              btn.innerHTML = origContent;
            }, 3000);
          });

          mediaWrap.appendChild(btn);
        }
      });
    }

    // 3. 小红书瀑布流笔记
    if (IS_XIAOHONGSHU) {
      document.querySelectorAll('section.note-item:not([data-clipdown-injected]), .note-item:not([data-clipdown-injected])').forEach(noteEl => {
        noteEl.setAttribute("data-clipdown-injected", "true");

        const coverWrap = noteEl.querySelector('.cover, .image-wrapper') || noteEl;
        const compStyle = window.getComputedStyle(coverWrap);
        if (compStyle.position === "static") {
          coverWrap.style.position = "relative";
        }

        const btn = document.createElement("div");
        btn.className = "clipdown-card-dl-btn";
        btn.title = "ClipDown 一键下载此笔记";
        btn.innerHTML = `
          <span class="clipdown-card-dl-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2v13M17 10l-5 5-5-5"/>
              <path d="M20 21H4"/>
            </svg>
          </span>
          <span class="clipdown-card-dl-label">下载</span>
        `;

        btn.addEventListener("click", (e) => {
          e.stopPropagation();
          e.preventDefault();

          const img = noteEl.querySelector("img");
          let cover = img ? img.src : "";
          if (cover) cover = cover.replace(/![a-zA-Z0-9_-]+$/, "");
          const title = noteEl.querySelector(".title, .name")?.textContent.trim() || "小红书笔记";

          if (cover) {
            btn.classList.add("success");
            btn.innerHTML = `✓ 已下载`;
            chrome.runtime.sendMessage({
              action: "DOWNLOAD_MEDIA_PC",
              url: cover,
              filename: `${title.substring(0, 30)}.jpg`,
              platform: "xiaohongshu"
            });
            setTimeout(() => {
              btn.classList.remove("success");
              btn.innerHTML = `下载`;
            }, 2500);
          }
        });

        coverWrap.appendChild(btn);
      });
    }

    // 4. YouTube 专属下载通道 (播放页操作栏 + Shorts 侧边栏 + 首页/搜索/频道瀑布流卡片)
    if (IS_YOUTUBE) {
      // 4.1 播放详情页操作栏按钮 (Watch Page Action Bar)
      if (window.location.pathname === "/watch") {
        const actionsHost = document.querySelector("#actions #top-level-buttons-computed, ytd-watch-metadata #actions");
        if (actionsHost && !actionsHost.querySelector(".clipdown-yt-action-btn")) {
          const ytBtn = document.createElement("button");
          ytBtn.className = "clipdown-yt-action-btn";
          ytBtn.title = "ClipDown 电脑直接下载 YouTube 视频";
          ytBtn.innerHTML = `
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2v13M17 10l-5 5-5-5"/>
              <path d="M20 21H4"/>
            </svg>
            <span>ClipDown 下载</span>
          `;
          ytBtn.addEventListener("click", async (e) => {
            e.stopPropagation();
            e.preventDefault();
            const origHtml = ytBtn.innerHTML;
            ytBtn.innerHTML = `<span class="clipdown-inline-spinner"></span> 正在抓取流...`;
            ytBtn.disabled = true;
            try {
              const inpageData = sniffPageMedia();
              const res = await new Promise(resolve => {
                chrome.runtime.sendMessage({
                  action: "DOWNLOAD_CURRENT_TAB_PC",
                  inpageData
                }, resolve);
              });
              if (res && res.success) {
                ytBtn.innerHTML = `✓ 已开始下载`;
                ytBtn.classList.add("success");
              } else {
                ytBtn.innerHTML = `❌ ${res?.error || "下载失败"}`;
              }
            } catch (err) {
              ytBtn.innerHTML = `❌ 下载异常`;
            }
            setTimeout(() => {
              ytBtn.disabled = false;
              ytBtn.classList.remove("success");
              ytBtn.innerHTML = origHtml;
            }, 3000);
          });
          actionsHost.prepend(ytBtn);
        }
      }

      // 4.2 YouTube Shorts 右侧操作栏下载按钮
      if (window.location.pathname.includes("/shorts/")) {
        const shortsActions = document.querySelector("ytd-reel-player-overlay-renderer #actions, ytd-reel-video-renderer #actions");
        if (shortsActions && !shortsActions.querySelector(".clipdown-shorts-dl-btn")) {
          const sBtn = document.createElement("button");
          sBtn.className = "clipdown-shorts-dl-btn";
          sBtn.title = "ClipDown 一键下载此 Shorts 视频";
          sBtn.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2v13M17 10l-5 5-5-5"/>
              <path d="M20 21H4"/>
            </svg>
            <span style="font-size:11px; margin-top:2px;">下载</span>
          `;
          sBtn.addEventListener("click", async (e) => {
            e.stopPropagation();
            e.preventDefault();
            sBtn.style.opacity = "0.6";
            try {
              const inpageData = sniffPageMedia();
              const res = await new Promise(resolve => {
                chrome.runtime.sendMessage({
                  action: "DOWNLOAD_CURRENT_TAB_PC",
                  inpageData
                }, resolve);
              });
              if (res && res.success) {
                const span = sBtn.querySelector("span");
                if (span) span.textContent = "已开始";
              }
            } catch (_) {}
            setTimeout(() => {
              sBtn.style.opacity = "1";
              const span = sBtn.querySelector("span");
              if (span) span.textContent = "下载";
            }, 2500);
          });
          shortsActions.appendChild(sBtn);
        }
      }

      // 4.3 首页 / 搜索 / 频道瀑布流视频卡片右下角按钮
      document.querySelectorAll(`
        ytd-rich-item-renderer:not([data-clipdown-injected]),
        ytd-video-renderer:not([data-clipdown-injected]),
        ytd-grid-video-renderer:not([data-clipdown-injected]),
        ytd-compact-video-renderer:not([data-clipdown-injected]),
        ytd-reel-item-renderer:not([data-clipdown-injected])
      `).forEach(card => {
        card.setAttribute("data-clipdown-injected", "true");
        const thumbWrap = card.querySelector("#thumbnail, ytd-thumbnail, .thumbnail-container");
        if (!thumbWrap) return;

        const compStyle = window.getComputedStyle(thumbWrap);
        if (compStyle.position === "static") {
          thumbWrap.style.position = "relative";
        }

        const btn = document.createElement("div");
        btn.className = "clipdown-card-dl-btn";
        btn.title = "ClipDown 一键下载此视频";
        btn.innerHTML = `
          <span class="clipdown-card-dl-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2v13M17 10l-5 5-5-5"/>
              <path d="M20 21H4"/>
            </svg>
          </span>
          <span class="clipdown-card-dl-label">下载</span>
        `;

        btn.addEventListener("click", async (e) => {
          e.stopPropagation();
          e.preventDefault();

          const link = card.querySelector('a#video-title-link, a#thumbnail, a[href*="/watch?v="], a[href*="/shorts/"]');
          if (!link) return;
          const href = link.getAttribute("href") || "";
          const targetUrl = href.startsWith("http") ? href : `https://www.youtube.com${href}`;
          const title = card.querySelector("#video-title, #video-title-link")?.textContent?.trim() || "YouTube_Video";

          const origContent = btn.innerHTML;
          btn.classList.add("loading");
          btn.innerHTML = `<span class="clipdown-inline-spinner"></span> 解析中...`;

          try {
            const res = await new Promise(resolve => {
              chrome.runtime.sendMessage({
                action: "RESOLVE_MEDIA",
                url: targetUrl,
                inpageData: { title, url: targetUrl }
              }, resolve);
            });

            if (res && res.success && res.data && res.data.items && res.data.items.length > 0) {
              const bestVideo = res.data.items.find(x => x.type === "video") || res.data.items[0];
              const ext = bestVideo.ext || "mp4";
              const safeTitle = title.substring(0, 40).replace(/[\\/:*?"<>|]/g, "_");

              btn.innerHTML = `⏳ 正在下载...`;
              chrome.runtime.sendMessage({
                action: "DOWNLOAD_MEDIA_PC",
                url: bestVideo.url,
                filename: `${safeTitle}.${ext}`,
                platform: "youtube"
              });

              btn.classList.remove("loading");
              btn.classList.add("success");
              btn.innerHTML = `✓ 已下载`;
            } else {
              btn.classList.remove("loading");
              btn.innerHTML = `❌ 未发现视频流`;
            }
          } catch (err) {
            btn.classList.remove("loading");
            btn.innerHTML = `❌ 下载失败`;
          }

          setTimeout(() => {
            btn.classList.remove("success", "loading");
            btn.innerHTML = origContent;
          }, 3000);
        });

        thumbWrap.appendChild(btn);
      });
    }
  }

  // ── 全局悬浮挂件与双端点赞+全页面采集体系 ──────────────────────────────────
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

    let currentMode = "single_media";
    if (isProfileOrLikesPage()) {
      currentMode = "page_full";
    } else if (IS_INSTAGRAM || IS_X) {
      currentMode = "liked";
    }

    let downloadConfig = { downloadSubfolder: "ClipDown", groupByPlatform: true };

    // 全页面采集运行时状态
    let accumulatedHarvesterMap = new Map();
    let isAutoScanning = false;
    let autoScanInterval = null;
    let harvesterFilter = "all"; // "all" | "video" | "image"

    // 监听来自扩展 Popup 的快捷指令
    chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
      if (msg.action === "OPEN_HARVESTER_MODE") {
        currentMode = "page_full";
        panel.classList.remove("hidden");
        renderCurrentPanel();
        sendResponse({ success: true });
      } else if (msg.action === "OPEN_LIKED_MODE") {
        currentMode = "liked";
        panel.classList.remove("hidden");
        renderCurrentPanel();
        sendResponse({ success: true });
      }
      return true;
    });

    // 读取用户配置
    chrome.storage.local.get(["download_subfolder", "download_group_by_platform"], (res) => {
      if (res) {
        if (res.download_subfolder !== undefined) downloadConfig.downloadSubfolder = res.download_subfolder;
        if (res.download_group_by_platform !== undefined) downloadConfig.groupByPlatform = res.download_group_by_platform;
      }
    });

    // 更新角标状态（Instagram 与 X 双端点赞统计）
    async function updateBadge() {
      let pendingCount = 0;
      if (IS_INSTAGRAM) {
        const { ins_liked_posts = [], ins_downloaded_posts = [] } = await chrome.storage.local.get([
          "ins_liked_posts",
          "ins_downloaded_posts"
        ]);
        pendingCount = ins_liked_posts.filter(p => !ins_downloaded_posts.includes(p.shortcode)).length;
      } else if (IS_X) {
        const { x_liked_posts = [], x_downloaded_posts = [] } = await chrome.storage.local.get([
          "x_liked_posts",
          "x_downloaded_posts"
        ]);
        pendingCount = x_liked_posts.filter(p => !x_downloaded_posts.includes(p.shortcode)).length;
      }

      if (pendingCount > 0) {
        badge.textContent = `❤️ ${pendingCount}`;
        badge.classList.remove("hidden");
      } else {
        badge.classList.add("hidden");
      }
    }
    updateBadge();

    // 点击悬浮球展开/收起
    trigger.addEventListener("click", (e) => {
      e.stopPropagation();
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
      if (currentMode === "liked") {
        renderLikedPanel();
      } else if (currentMode === "page_full") {
        renderPageHarvesterPanel();
      } else {
        renderSingleMediaPanel();
      }
    }

    // 辅助头部快捷按钮
    function renderHeaderTools(active) {
      const hasLiked = IS_INSTAGRAM || IS_X;
      return `
        <div class="clipdown-header-tools">
          <button class="clipdown-tool-btn ${active === 'single_media' ? 'active' : ''}" id="clipdown-mode-single" title="单篇嗅探模式">🔍</button>
          ${hasLiked ? `<button class="clipdown-tool-btn ${active === 'liked' ? 'active' : ''}" id="clipdown-mode-liked" title="点赞待下载清单">❤️</button>` : ""}
          <button class="clipdown-tool-btn ${active === 'page_full' ? 'active' : ''}" id="clipdown-mode-page" title="全页面/主页采集">📦</button>
          <button class="clipdown-tool-btn" id="clipdown-btn-close" title="收起">×</button>
        </div>
      `;
    }

    function bindHeaderToolEvents() {
      panel.querySelector("#clipdown-btn-close")?.addEventListener("click", (e) => {
        e.stopPropagation();
        panel.classList.add("hidden");
      });
      panel.querySelector("#clipdown-mode-single")?.addEventListener("click", (e) => {
        e.stopPropagation();
        currentMode = "single_media";
        renderCurrentPanel();
      });
      panel.querySelector("#clipdown-mode-liked")?.addEventListener("click", (e) => {
        e.stopPropagation();
        currentMode = "liked";
        renderCurrentPanel();
      });
      panel.querySelector("#clipdown-mode-page")?.addEventListener("click", (e) => {
        e.stopPropagation();
        currentMode = "page_full";
        renderCurrentPanel();
      });
    }

    // ── 模式 1：点赞监控待下载清单（同时适配 Instagram 与 X 瀑布流）─────────────
    async function renderLikedPanel() {
      const isX = IS_X;
      const platformKey = isX ? "X (Twitter)" : "Instagram";
      const storageKeyLiked = isX ? "x_liked_posts" : "ins_liked_posts";
      const storageKeyDownloaded = isX ? "x_downloaded_posts" : "ins_downloaded_posts";

      const store = await chrome.storage.local.get([storageKeyLiked, storageKeyDownloaded]);
      const likedPosts = store[storageKeyLiked] || [];
      const downloadedPosts = store[storageKeyDownloaded] || [];

      const folderName = isX ? "X" : "Instagram";
      const subfolder = downloadConfig.downloadSubfolder ? `Downloads/${downloadConfig.downloadSubfolder}/${folderName}` : `Downloads/${folderName}`;
      const pendingPosts = likedPosts.filter(p => !downloadedPosts.includes(p.shortcode));
      const pendingCount = pendingPosts.length;

      panel.innerHTML = `
        <div class="clipdown-panel-header">
          <div class="clipdown-badge-row">
            <span class="clipdown-pill clipdown-pill-brand">${platformKey}</span>
            <span class="clipdown-pill clipdown-pill-sub">❤️ 点赞待下载清单</span>
          </div>
          ${renderHeaderTools("liked")}
        </div>

        <div class="clipdown-toolbar-row">
          <span style="color:#CBD5E1; font-weight:600;">共 ${likedPosts.length} 条已点赞 · <strong style="color:#C084FC;">${pendingCount} 待下载</strong></span>
          <div>
            <button class="clipdown-text-btn" id="btn-select-all">全选</button>
            <button class="clipdown-text-btn" id="btn-unselect-all">取消全选</button>
          </div>
        </div>

        <div class="clipdown-liked-list" id="clipdown-liked-list">
          ${likedPosts.length > 0 ? likedPosts.map((post) => {
            const isDownloaded = downloadedPosts.includes(post.shortcode);
            const mediaTypeStr = post.items && post.items.some(x => x.type === "video") ? "🎬 视频" : `🖼️ ${(post.items || []).length}张原图`;
            return `
              <div class="clipdown-liked-item ${isDownloaded ? "is-downloaded" : ""}" data-shortcode="${post.shortcode}">
                <input type="checkbox" class="clipdown-post-check" data-shortcode="${post.shortcode}" ${!isDownloaded ? "checked" : ""} />
                <img src="${post.cover || ''}" class="clipdown-post-thumb" title="首图预览" />
                <div class="clipdown-post-info">
                  <div class="clipdown-post-author">@${escapeHtml(post.author)}</div>
                  <div class="clipdown-post-title" title="${escapeHtml(post.title)}">${escapeHtml(post.title)}</div>
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
              <strong style="color: #FFFFFF; font-size: 13px;">${platformKey} 智能点赞监控中</strong><br/>
              刷动态时点赞（点击爱心 ❤️ 或双击），已点赞的内容将自动捕获并在此显示首图，一键下载！
            </div>
          `}
        </div>

        <div class="clipdown-panel-actions">
          <button class="clipdown-action-btn clipdown-btn-batch" id="btn-download-selected-posts">
            💻 一键下载选中的点赞内容
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

      bindHeaderToolEvents();

      // 全选 / 取消全选
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

      const btnDownloadSelected = panel.querySelector("#btn-download-selected-posts");
      function updateDownloadBtnCount() {
        const checkedCount = Array.from(checkBoxes).filter(cb => cb.checked).length;
        if (btnDownloadSelected) {
          btnDownloadSelected.textContent = checkedCount > 0
            ? `💻 一键下载选中的 ${checkedCount} 项`
            : "请先勾选要下载的内容";
          btnDownloadSelected.disabled = checkedCount === 0;
        }
      }
      checkBoxes.forEach(cb => {
        cb.addEventListener("change", updateDownloadBtnCount);
      });
      updateDownloadBtnCount();

      // 执行下载选中的点赞内容
      if (btnDownloadSelected) {
        btnDownloadSelected.addEventListener("click", async (e) => {
          e.stopPropagation();
          const selectedCodes = Array.from(checkBoxes).filter(cb => cb.checked).map(cb => cb.dataset.shortcode);
          if (selectedCodes.length === 0) return;

          const postsToDownload = likedPosts.filter(p => selectedCodes.includes(p.shortcode));
          if (postsToDownload.length === 0) return;

          btnDownloadSelected.disabled = true;
          btnDownloadSelected.textContent = `⏳ 正在下载 ${postsToDownload.length} 项...`;
          btnDownloadSelected.classList.add("clipdown-btn-success");

          const targetPlatform = isX ? "x" : "instagram";

          for (const post of postsToDownload) {
            const rawTitle = (post.title || `${folderName}_Post`).substring(0, 40).replace(/[\\/:*?"<>|]/g, "_");
            const images = (post.items || []).filter(x => x.type === "image");
            const videos = (post.items || []).filter(x => x.type === "video");

            if (videos.length > 0) {
              chrome.runtime.sendMessage({
                action: "DOWNLOAD_MEDIA_PC",
                url: videos[0].url,
                filename: `${rawTitle}.mp4`,
                platform: targetPlatform
              });
            } else if (images.length > 1) {
              chrome.runtime.sendMessage({
                action: "DOWNLOAD_BATCH_PC",
                items: images,
                baseTitle: rawTitle,
                platform: targetPlatform
              });
            } else if (images.length === 1) {
              chrome.runtime.sendMessage({
                action: "DOWNLOAD_MEDIA_PC",
                url: images[0].url,
                filename: `${rawTitle}.jpg`,
                platform: targetPlatform
              });
            } else if (post.cover) {
              chrome.runtime.sendMessage({
                action: "DOWNLOAD_MEDIA_PC",
                url: post.cover,
                filename: `${rawTitle}.jpg`,
                platform: targetPlatform
              });
            }
          }

          // 记录已下载（持久化）
          const updatedDownloaded = Array.from(new Set([...downloadedPosts, ...selectedCodes]));
          const updateObj = {};
          updateObj[storageKeyDownloaded] = updatedDownloaded;
          await chrome.storage.local.set(updateObj);

          btnDownloadSelected.textContent = `✓ 已开始下载 ${postsToDownload.length} 项点赞内容`;
          updateBadge();

          setTimeout(() => {
            renderLikedPanel();
          }, 1800);
        });
      }

      // 清空已下载记录
      const btnClearDownloaded = panel.querySelector("#btn-clear-downloaded");
      if (btnClearDownloaded) {
        btnClearDownloaded.addEventListener("click", async (e) => {
          e.stopPropagation();
          const remainingLiked = likedPosts.filter(p => !downloadedPosts.includes(p.shortcode));
          const obj = {};
          obj[storageKeyLiked] = remainingLiked;
          await chrome.storage.local.set(obj);
          renderLikedPanel();
          updateBadge();
        });
      }

      // 清空全部点赞列表
      const btnClearAllLiked = panel.querySelector("#btn-clear-all-liked");
      if (btnClearAllLiked) {
        btnClearAllLiked.addEventListener("click", async (e) => {
          e.stopPropagation();
          const obj = {};
          obj[storageKeyLiked] = [];
          await chrome.storage.local.set(obj);
          renderLikedPanel();
          updateBadge();
        });
      }
    }

    // ── 模式 2：全页面/主页资源深度采集与双端批量下载 (X / Instagram / 小红书) ─────
    function renderPageHarvesterPanel() {
      const currentViewportItems = harvestCurrentPageItems();
      currentViewportItems.forEach(item => {
        if (!accumulatedHarvesterMap.has(item.shortcode)) {
          accumulatedHarvesterMap.set(item.shortcode, item);
        }
      });

      const allItems = Array.from(accumulatedHarvesterMap.values());
      const filteredItems = allItems.filter(item => {
        if (harvesterFilter === "video") return item.items?.some(x => x.type === "video") || item.isVideo;
        if (harvesterFilter === "image") return !item.items?.some(x => x.type === "video") && !item.isVideo;
        return true;
      });

      const videoCount = allItems.filter(item => item.items?.some(x => x.type === "video") || item.isVideo).length;
      const imageCount = allItems.length - videoCount;

      let platformName = "网页";
      if (IS_X) platformName = "X (Twitter)";
      else if (IS_INSTAGRAM) platformName = "Instagram";
      else if (IS_XIAOHONGSHU) platformName = "小红书";

      const subfolder = downloadConfig.downloadSubfolder
        ? `Downloads/${downloadConfig.downloadSubfolder}/${platformName}_Batch`
        : `Downloads/${platformName}_Batch`;

      panel.innerHTML = `
        <div class="clipdown-panel-header">
          <div class="clipdown-badge-row">
            <span class="clipdown-pill clipdown-pill-brand">${platformName}</span>
            <span class="clipdown-pill clipdown-pill-sub">📦 全页面批量采集</span>
          </div>
          ${renderHeaderTools("page_full")}
        </div>

        <div class="clipdown-harvester-bar">
          <button class="clipdown-scan-btn ${isAutoScanning ? 'clipdown-btn-scanning' : ''}" id="btn-toggle-auto-scan">
            ${isAutoScanning ? '⏹️ 正在深度滚动扫描...点击停止' : '⚡ 自动向下滚动深度扫描'}
          </button>
        </div>

        <div class="clipdown-toolbar-row" style="margin-top: 6px;">
          <div class="clipdown-filter-chips">
            <span class="clipdown-filter-chip ${harvesterFilter === 'all' ? 'active' : ''}" data-filter="all">全部 ${allItems.length}</span>
            <span class="clipdown-filter-chip ${harvesterFilter === 'video' ? 'active' : ''}" data-filter="video">🎬 视频 ${videoCount}</span>
            <span class="clipdown-filter-chip ${harvesterFilter === 'image' ? 'active' : ''}" data-filter="image">🖼️ 图文 ${imageCount}</span>
          </div>
          <div class="clipdown-sel-actions">
            <button class="clipdown-text-btn" id="btn-harvester-select-all">全选</button>
            <button class="clipdown-text-btn" id="btn-harvester-unselect-all">取消</button>
          </div>
        </div>

        <div class="clipdown-liked-list" id="clipdown-harvester-list">
          ${filteredItems.length > 0 ? filteredItems.map((item) => {
            const isVideo = item.items?.some(x => x.type === "video") || item.isVideo;
            const typeText = isVideo ? "🎬 视频" : `🖼️ ${item.items?.length || 1}图`;
            return `
              <div class="clipdown-liked-item" data-shortcode="${item.shortcode}">
                <input type="checkbox" class="clipdown-harvester-check" data-shortcode="${item.shortcode}" checked />
                <img src="${item.cover || ''}" class="clipdown-post-thumb" title="预览" />
                <div class="clipdown-post-info">
                  <div class="clipdown-post-author">@${escapeHtml(item.author)}</div>
                  <div class="clipdown-post-title" title="${escapeHtml(item.title)}">${escapeHtml(item.title)}</div>
                  <div class="clipdown-post-meta">
                    <span>${typeText}</span>
                    <span class="clipdown-tag-pending">待下载</span>
                  </div>
                </div>
              </div>
            `;
          }).join("") : `
            <div class="clipdown-empty-text">
              <div style="font-size: 24px; margin-bottom: 6px;">📦</div>
              <strong style="color: #FFFFFF; font-size: 13px;">点击上方按钮自动向下滚动扫描</strong><br/>
              支持博主主页、个人点赞页、媒体页。自动深度抓取页面所有卡片！
            </div>
          `}
        </div>

        <div class="clipdown-panel-actions">
          <button class="clipdown-action-btn clipdown-btn-batch" id="btn-download-harvester-pc">
            💻 一键下载全部选中的 ${filteredItems.length} 项到电脑
          </button>
          <button class="clipdown-action-btn clipdown-btn-mobile" id="btn-push-harvester-mobile">
            🚀 全量推送到手机下载 (${filteredItems.length} 项)
          </button>
        </div>

        <div class="clipdown-panel-footer">
          <span class="clipdown-footer-path">📂 存至: <strong>${subfolder}</strong></span>
        </div>
      `;

      bindHeaderToolEvents();

      const btnToggleScan = panel.querySelector("#btn-toggle-auto-scan");
      if (btnToggleScan) {
        btnToggleScan.addEventListener("click", (e) => {
          e.stopPropagation();
          if (isAutoScanning) {
            stopAutoScan();
          } else {
            startAutoScan();
          }
        });
      }

      function startAutoScan() {
        isAutoScanning = true;
        let noNewCount = 0;

        autoScanInterval = setInterval(() => {
          window.scrollBy({ top: 900, behavior: "smooth" });

          const newlyFound = harvestCurrentPageItems();
          let added = 0;
          newlyFound.forEach(it => {
            if (!accumulatedHarvesterMap.has(it.shortcode)) {
              accumulatedHarvesterMap.set(it.shortcode, it);
              added++;
            }
          });

          if (added === 0) {
            noNewCount++;
            if (noNewCount >= 12 || (window.innerHeight + window.scrollY >= document.body.offsetHeight - 80)) {
              stopAutoScan();
              return;
            }
          } else {
            noNewCount = 0;
          }

          renderPageHarvesterPanel();
        }, 900);

        renderPageHarvesterPanel();
      }

      function stopAutoScan() {
        isAutoScanning = false;
        if (autoScanInterval) {
          clearInterval(autoScanInterval);
          autoScanInterval = null;
        }
        renderPageHarvesterPanel();
      }

      panel.querySelectorAll(".clipdown-filter-chip").forEach(chip => {
        chip.addEventListener("click", (e) => {
          e.stopPropagation();
          harvesterFilter = chip.dataset.filter;
          renderPageHarvesterPanel();
        });
      });

      const checkBoxes = panel.querySelectorAll(".clipdown-harvester-check");
      const btnSelectAll = panel.querySelector("#btn-harvester-select-all");
      const btnUnselectAll = panel.querySelector("#btn-harvester-unselect-all");

      if (btnSelectAll) {
        btnSelectAll.addEventListener("click", (e) => {
          e.stopPropagation();
          checkBoxes.forEach(cb => cb.checked = true);
          updateHarvesterButtons();
        });
      }
      if (btnUnselectAll) {
        btnUnselectAll.addEventListener("click", (e) => {
          e.stopPropagation();
          checkBoxes.forEach(cb => cb.checked = false);
          updateHarvesterButtons();
        });
      }

      const btnDownloadPc = panel.querySelector("#btn-download-harvester-pc");
      const btnPushMobile = panel.querySelector("#btn-push-harvester-mobile");

      function updateHarvesterButtons() {
        const checkedCount = Array.from(checkBoxes).filter(cb => cb.checked).length;
        if (btnDownloadPc) {
          btnDownloadPc.textContent = checkedCount > 0
            ? `💻 一键下载全部选中的 ${checkedCount} 项到电脑`
            : "请先勾选要下载的作品";
          btnDownloadPc.disabled = checkedCount === 0;
        }
        if (btnPushMobile) {
          btnPushMobile.textContent = checkedCount > 0
            ? `🚀 全量推送到手机下载 (${checkedCount} 项)`
            : "请先勾选要推送的作品";
          btnPushMobile.disabled = checkedCount === 0;
        }
      }
      checkBoxes.forEach(cb => cb.addEventListener("change", updateHarvesterButtons));
      updateHarvesterButtons();

      if (btnDownloadPc) {
        btnDownloadPc.addEventListener("click", (e) => {
          e.stopPropagation();
          const selectedCodes = Array.from(checkBoxes).filter(cb => cb.checked).map(cb => cb.dataset.shortcode);
          const selectedItems = allItems.filter(it => selectedCodes.includes(it.shortcode));
          if (selectedItems.length === 0) return;

          btnDownloadPc.disabled = true;
          btnDownloadPc.textContent = `⏳ 正在批量下载 ${selectedItems.length} 项作品...`;
          btnDownloadPc.classList.add("clipdown-btn-success");

          chrome.runtime.sendMessage({
            action: "DOWNLOAD_PAGE_BATCH",
            posts: selectedItems,
            platform: platformName
          }, (res) => {
            btnDownloadPc.textContent = `✓ 已开始下载 ${res?.count || selectedItems.length} 项作品！`;
            setTimeout(() => {
              updateHarvesterButtons();
              btnDownloadPc.classList.remove("clipdown-btn-success");
            }, 2500);
          });
        });
      }

      if (btnPushMobile) {
        btnPushMobile.addEventListener("click", (e) => {
          e.stopPropagation();
          const selectedCodes = Array.from(checkBoxes).filter(cb => cb.checked).map(cb => cb.dataset.shortcode);
          const selectedItems = allItems.filter(it => selectedCodes.includes(it.shortcode));
          if (selectedItems.length === 0) return;

          btnPushMobile.disabled = true;
          btnPushMobile.textContent = `🚀 正在全量推送 ${selectedItems.length} 项到手机...`;

          chrome.runtime.sendMessage({
            action: "PUSH_BATCH_TO_PHONE",
            posts: selectedItems
          }, (res) => {
            if (res && res.success) {
              btnPushMobile.textContent = `✓ 成功推送 ${selectedItems.length} 项到手机队列！`;
              btnPushMobile.classList.add("clipdown-btn-success");
            } else {
              btnPushMobile.textContent = "❌ 推送失败 (请确认手机 ClipDown 配对)";
            }
            setTimeout(() => {
              updateHarvesterButtons();
              btnPushMobile.classList.remove("clipdown-btn-success");
            }, 2500);
          });
        });
      }
    }

    // ── 模式 3：通用单篇嗅探面板 ─────────────────────────────────────────────
    function renderSingleMediaPanel() {
      panel.innerHTML = `
        <div class="clipdown-panel-header">
          <div class="clipdown-badge-row">
            <span class="clipdown-pill clipdown-pill-brand">网页探测</span>
            <span class="clipdown-pill clipdown-pill-sub">单帖深度解析</span>
          </div>
          ${renderHeaderTools("single_media")}
        </div>
        <div class="clipdown-loading-box">
          <div class="clipdown-spinner"></div>
          <div class="clipdown-loading-text">正在深度解析清晰度与媒体流...</div>
        </div>
      `;

      bindHeaderToolEvents();

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
          ${renderHeaderTools("single_media")}
        </div>

        <div class="clipdown-media-row">
          ${cover ? `<img src="${cover}" class="clipdown-media-cover" alt="Cover" />` : ""}
          <div class="clipdown-media-title" title="${escapeHtml(title)}">${escapeHtml(title)}</div>
        </div>

        <div class="clipdown-streams-list" id="clipdown-streams-list">
          ${items.length > 0 ? items.map((item, idx) => `
            <div class="clipdown-stream-item">
              <span class="clipdown-stream-label" title="${escapeHtml(item.label)}">${escapeHtml(item.label)}</span>
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

      bindHeaderToolEvents();

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
          ${renderHeaderTools("single_media")}
        </div>
        <div class="clipdown-empty-text">未直接探测到下载流，可点击下方按钮推送到手机端深度解析</div>
        <div class="clipdown-panel-actions">
          <button class="clipdown-action-btn clipdown-btn-mobile" id="clipdown-mobile-btn">
            🚀 推送到手机下载
          </button>
        </div>
      `;
      bindHeaderToolEvents();
      panel.querySelector("#clipdown-mobile-btn")?.addEventListener("click", (e) => {
        e.stopPropagation();
        chrome.runtime.sendMessage({ action: "ENQUEUE_URL", url: window.location.href });
      });
    }

    // ── 监听体系 1：Instagram 点赞监听器（全面重构，双轨捕获）──────────────────
    if (IS_INSTAGRAM) {
      async function onPostLiked(articleEl) {
        if (!articleEl) return;
        const postData = extractMediaFromInstagramArticle(articleEl);
        if (!postData || !postData.shortcode) return;

        // 若是图集或单图，异步请求完整详情补充所有图集图片
        if (postData.isCarousel || (postData.items && postData.items.length <= 1)) {
          chrome.runtime.sendMessage({
            action: "RESOLVE_INSTAGRAM_POST",
            shortcode: postData.shortcode,
            inpageItems: postData.items
          }, async (resp) => {
            if (resp && resp.success && resp.data && resp.data.items && resp.data.items.length > 0) {
              postData.items = resp.data.items;
              if (resp.data.title) postData.title = resp.data.title;
              if (resp.data.author) postData.author = resp.data.author;

              const { ins_liked_posts = [] } = await chrome.storage.local.get(["ins_liked_posts"]);
              const idx = ins_liked_posts.findIndex(p => p.shortcode === postData.shortcode);
              if (idx >= 0) {
                ins_liked_posts[idx] = postData;
                await chrome.storage.local.set({ ins_liked_posts });
                if (!panel.classList.contains("hidden") && currentMode === "liked") {
                  renderLikedPanel();
                }
              }
            }
          });
        }

        const { ins_liked_posts = [] } = await chrome.storage.local.get(["ins_liked_posts"]);
        const existingIdx = ins_liked_posts.findIndex(p => p.shortcode === postData.shortcode);
        if (existingIdx >= 0) {
          ins_liked_posts[existingIdx] = postData;
        } else {
          ins_liked_posts.unshift(postData);
        }

        const pruned = ins_liked_posts.slice(0, 60);
        await chrome.storage.local.set({ ins_liked_posts: pruned });
        updateBadge();

        // 触发展开角标心跳动效
        badge.classList.add("clipdown-badge-heartbeat");
        setTimeout(() => badge.classList.remove("clipdown-badge-heartbeat"), 650);

        if (!panel.classList.contains("hidden") && currentMode === "liked") {
          renderLikedPanel();
        }
      }

      // 1. 点击捕获爱心按钮与帖子操作区首个按钮
      document.addEventListener("click", (e) => {
        const btn = e.target.closest('button, [role="button"], span, div');
        if (!btn) return;

        const isLikeTarget =
          btn.matches('[aria-label="Like" i], [aria-label="赞"], [aria-label="喜欢"], [aria-label="点赞"]') ||
          btn.querySelector('[aria-label="Like" i], [aria-label="赞"], [aria-label="喜欢"], [aria-label="点赞"]') ||
          btn.querySelector('svg title')?.textContent?.match(/like|赞|喜欢/i) ||
          btn.closest('section button:first-of-type, section [role="button"]:first-of-type');

        if (isLikeTarget) {
          const article = btn.closest("article") || document.querySelector("article");
          if (article) {
            setTimeout(() => onPostLiked(article), 150);
          }
        }
      }, true);

      // 2. 双击图片点赞捕获 (Instagram 经典手势)
      document.addEventListener("dblclick", (e) => {
        const article = e.target.closest("article");
        if (article) {
          setTimeout(() => onPostLiked(article), 200);
        }
      }, true);

      // 3. MutationObserver 监听已点赞状态变化 (覆盖 React 替换 node 与属性修改)
      const observer = new MutationObserver((mutations) => {
        let shouldInject = false;

        for (const m of mutations) {
          if (m.type === "childList") {
            if (m.addedNodes.length > 0) shouldInject = true;
            for (const node of m.addedNodes) {
              if (node.nodeType === Node.ELEMENT_NODE) {
                const isLiked = node.matches?.('[aria-label="Unlike" i], [aria-label="取消赞"], [aria-label="取消喜欢"], [color="rgb(255, 48, 64)"], [fill="rgb(255, 48, 64)"]') ||
                                node.querySelector?.('[aria-label="Unlike" i], [aria-label="取消赞"], [aria-label="取消喜欢"], [color="rgb(255, 48, 64)"], [fill="rgb(255, 48, 64)"]');
                if (isLiked) {
                  const article = node.closest("article") || document.querySelector("article");
                  if (article) onPostLiked(article);
                }
              }
            }
          } else if (m.type === "attributes") {
            const el = m.target;
            const label = el.getAttribute("aria-label");
            if (label && (label.toLowerCase() === "unlike" || label.includes("取消赞") || label.includes("取消喜欢"))) {
              const article = el.closest("article") || document.querySelector("article");
              if (article) onPostLiked(article);
            }
          }
        }

        if (shouldInject) {
          injectCardDownloadButtons();
        }
      });

      observer.observe(document.body, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ["aria-label", "data-testid"]
      });
    }

    // ── 监听体系 2：X (Twitter) 点赞监听器（全面重构）─────────────────────────
    if (IS_X) {
      async function onXTweetLiked(tweetEl) {
        if (!tweetEl) return;
        const tweetData = extractMediaFromXTweet(tweetEl);
        if (!tweetData || !tweetData.shortcode) return;

        const { x_liked_posts = [] } = await chrome.storage.local.get(["x_liked_posts"]);
        const existingIdx = x_liked_posts.findIndex(p => p.shortcode === tweetData.shortcode);
        if (existingIdx >= 0) {
          x_liked_posts[existingIdx] = tweetData;
        } else {
          x_liked_posts.unshift(tweetData);
        }

        const pruned = x_liked_posts.slice(0, 60);
        await chrome.storage.local.set({ x_liked_posts: pruned });
        updateBadge();

        badge.classList.add("clipdown-badge-heartbeat");
        setTimeout(() => badge.classList.remove("clipdown-badge-heartbeat"), 650);

        if (!panel.classList.contains("hidden") && currentMode === "liked") {
          renderLikedPanel();
        }
      }

      // 1. 点击捕获爱心按钮 [data-testid="like"]
      document.addEventListener("click", (e) => {
        const likeBtn = e.target.closest('[data-testid="like"]');
        if (likeBtn) {
          const tweetEl = likeBtn.closest('article[data-testid="tweet"]');
          if (tweetEl) {
            setTimeout(() => onXTweetLiked(tweetEl), 150);
          }
        }
      }, true);

      // 2. MutationObserver 监听 data-testid="unlike" 与新卡片挂载
      const xObserver = new MutationObserver((mutations) => {
        let shouldInject = false;

        for (const m of mutations) {
          if (m.type === "childList") {
            if (m.addedNodes.length > 0) shouldInject = true;
            for (const node of m.addedNodes) {
              if (node.nodeType === Node.ELEMENT_NODE) {
                const unlikeEl = node.matches?.('[data-testid="unlike"]') ? node : node.querySelector?.('[data-testid="unlike"]');
                if (unlikeEl) {
                  const tweetEl = unlikeEl.closest('article[data-testid="tweet"]');
                  if (tweetEl) onXTweetLiked(tweetEl);
                }
              }
            }
          } else if (m.type === "attributes" && m.attributeName === "data-testid") {
            if (m.target.getAttribute("data-testid") === "unlike") {
              const tweetEl = m.target.closest('article[data-testid="tweet"]');
              if (tweetEl) onXTweetLiked(tweetEl);
            }
          }
        }

        if (shouldInject) {
          injectCardDownloadButtons();
        }
      });

      xObserver.observe(document.body, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ["data-testid"]
      });
    }

    // ── 初始化卡片下载按钮注入与滚动监听 ─────────────────────────────────────
    injectCardDownloadButtons();

    let scrollTimer = null;
    window.addEventListener("scroll", () => {
      if (!scrollTimer) {
        scrollTimer = setTimeout(() => {
          injectCardDownloadButtons();
          scrollTimer = null;
        }, 250);
      }
    }, { passive: true });
  }

  // 延迟注入防白屏干扰，并立刻启动初次卡片按钮注入
  setTimeout(() => {
    injectFloatingWidget();
    injectCardDownloadButtons();
  }, 1200);
})();
