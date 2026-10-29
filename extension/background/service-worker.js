/**
 * ClipDown 跨端助手 - Background Service Worker
 * 深度针对 X (Twitter)、Instagram、YouTube、小红书的媒体提取与 PC 本地直接下载
 */

const PLATFORMS = {
  x: {
    name: "X (Twitter)",
    domains: [".x.com", "x.com", ".twitter.com", "twitter.com"],
    requiredTokens: ["auth_token="],
    urlPattern: /x\.com|twitter\.com/i
  },
  instagram: {
    name: "Instagram",
    domains: [".instagram.com", "instagram.com", "www.instagram.com"],
    requiredTokens: ["sessionid="],
    urlPattern: /instagram\.com/i
  },
  xiaohongshu: {
    name: "小红书",
    domains: [".xiaohongshu.com", "xiaohongshu.com", "www.xiaohongshu.com"],
    requiredTokens: ["web_session=", "a1="],
    urlPattern: /xiaohongshu\.com|xhslink\.com|xhslink\.cn/i
  },
  youtube: {
    name: "YouTube",
    domains: [".youtube.com", "youtube.com", ".google.com"],
    requiredTokens: ["LOGIN_INFO=", "SAPISID=", "VISITOR_INFO1_LIVE="],
    urlPattern: /youtube\.com|youtu\.be/i
  },
  facebook: {
    name: "Facebook",
    domains: [".facebook.com", "facebook.com", "www.facebook.com"],
    requiredTokens: ["c_user=", "xs="],
    urlPattern: /facebook\.com/i
  }
};

// 提取指定域名的 Cookie 字符串
async function getCookiesForDomains(domains) {
  const cookieMap = new Map();
  for (const domain of domains) {
    try {
      const cookies = await chrome.cookies.getAll({ domain });
      for (const c of cookies) {
        cookieMap.set(c.name, c.value);
      }
    } catch (e) {
      console.warn("getAll cookies failed for domain:", domain, e);
    }
  }
  const pairs = [];
  for (const [k, v] of cookieMap.entries()) {
    pairs.push(`${k}=${v}`);
  }
  return pairs.join("; ");
}

// 检查某个平台是否已在电脑浏览器登录
async function checkPlatformCookie(platformKey) {
  const cfg = PLATFORMS[platformKey];
  if (!cfg) return { loggedIn: false, cookie: "" };

  const cookieStr = await getCookiesForDomains(cfg.domains);
  let loggedIn = false;
  if (cookieStr) {
    loggedIn = cfg.requiredTokens.some(token => cookieStr.includes(token));
  }
  return {
    platform: platformKey,
    name: cfg.name,
    loggedIn,
    cookie: cookieStr
  };
}

// 检查所有受支持平台的登录态
async function checkAllPlatformCookies() {
  const result = {};
  for (const key of Object.keys(PLATFORMS)) {
    result[key] = await checkPlatformCookie(key);
  }
  return result;
}

// 强力清洗与规范化手机局域网 URL（根除空格、全角冒号与格式错误）
function sanitizePhoneUrl(rawUrl) {
  if (!rawUrl) return "http://192.168.1.108:8765";
  let url = String(rawUrl).trim();
  // 替换全角冒号、全角句号
  url = url.replace(/：/g, ":").replace(/。/g, ".");
  // 移除所有内部空白字符（包括空格、制表符、零宽字符）
  url = url.replace(/\s+/g, "");
  // 自动补全 http://
  if (!/^https?:\/\//i.test(url)) {
    url = "http://" + url;
  }
  // 移除末尾多余斜杠
  url = url.replace(/\/+$/, "");
  return url;
}

// 获取配对设置
async function getPairingConfig() {
  const data = await chrome.storage.local.get(["phoneUrl", "phonePin", "autoSync"]);
  const cleanUrl = sanitizePhoneUrl(data.phoneUrl);
  const cleanPin = String(data.phonePin || "").trim().replace(/\s+/g, "");
  return {
    phoneUrl: cleanUrl,
    phonePin: cleanPin,
    autoSync: !!data.autoSync
  };
}

// 局域网发送 HTTP 请求到手机
async function fetchFromPhone(endpoint, options = {}) {
  const { phoneUrl, phonePin } = await getPairingConfig();
  const safeEndpoint = endpoint.startsWith("/") ? endpoint : `/${endpoint}`;
  const url = `${phoneUrl}${safeEndpoint}`;

  const headers = {
    "Content-Type": "application/json",
    "X-Sync-PIN": phonePin,
    ...(options.headers || {})
  };

  try {
    const resp = await fetch(url, {
      ...options,
      headers,
      signal: AbortSignal.timeout(8000)
    });

    if (!resp.ok) {
      let errMsg = `HTTP ${resp.status} ${resp.statusText}`;
      try {
        const errJson = await resp.json();
        if (errJson && errJson.message) errMsg = errJson.message;
      } catch (_) {}
      throw new Error(errMsg);
    }

    return await resp.json();
  } catch (err) {
    let errStr = err.message || (err.name ? `${err.name}` : String(err));
    if (err.name === "TimeoutError") {
      errStr = "请求超时：手机未开启局域网同步服务，或电脑与手机不在同一 Wi-Fi";
    } else if (errStr.includes("Failed to fetch") || errStr.includes("NetworkError")) {
      errStr = "连接失败：请确认手机 ClipDown 已开启同步、IP 地址正确且处于同一局域网";
    } else if (errStr.includes("Failed to parse URL")) {
      errStr = `服务地址格式有误: ${url}`;
    }
    throw new Error(errStr);
  }
}

// ── PC 电脑端原生直接下载与路径配置引擎 ────────────────────────────────────
async function getDownloadConfig() {
  const data = await chrome.storage.local.get([
    "downloadSubfolder",
    "groupByPlatform",
    "promptSaveAs"
  ]);

  let subfolder = data.downloadSubfolder !== undefined ? String(data.downloadSubfolder).trim() : "ClipDown";
  // 移除开头的 / 或 \ 以及末尾的 / 或 \
  subfolder = subfolder.replace(/^[\\/]+|[\\/]+$/g, "");
  // Windows/Chromium 非法字符过滤
  subfolder = subfolder.replace(/[:*?"<>|]/g, "_");

  return {
    downloadSubfolder: subfolder,
    groupByPlatform: data.groupByPlatform !== false, // 默认 true
    promptSaveAs: !!data.promptSaveAs
  };
}

async function computeDownloadTarget(filename, platform, forceSaveAs = false) {
  const cfg = await getDownloadConfig();
  const cleanName = filename.replace(/[\\/:*?"<>|]/g, "_").trim();

  const parts = [];
  if (cfg.downloadSubfolder) {
    parts.push(cfg.downloadSubfolder);
  }
  if (cfg.groupByPlatform && platform) {
    const platformFolder = PLATFORMS[platform]?.name?.replace(/[\\/:*?"<>|]/g, "_") || platform;
    parts.push(platformFolder);
  }
  parts.push(cleanName);

  const fullPath = parts.join("/");
  const folder = parts.slice(0, -1).join("/");
  const shouldPrompt = forceSaveAs || cfg.promptSaveAs;

  return {
    fullPath,
    folder,
    saveAs: shouldPrompt
  };
}

async function downloadToPc(url, filename, platform = "", forceSaveAs = false) {
  const target = await computeDownloadTarget(filename, platform, forceSaveAs);

  return new Promise((resolve, reject) => {
    chrome.downloads.download({
      url,
      filename: target.fullPath,
      saveAs: target.saveAs,
      conflictAction: "uniquify"
    }, (downloadId) => {
      if (chrome.runtime.lastError) {
        reject(new Error(chrome.runtime.lastError.message));
      } else {
        resolve({
          downloadId,
          filename: target.fullPath,
          folder: target.folder,
          saveAs: target.saveAs
        });
      }
    });
  });
}

function parseInstagramMediaObj(media) {
  if (!media) return null;
  const author = media.owner?.username || media.user?.username || "";
  const title = media.edge_media_to_caption?.edges?.[0]?.node?.text || media.caption?.text || "";
  const items = [];

  // 1. 图集 edge_sidecar_to_children (GraphQL)
  if (media.edge_sidecar_to_children?.edges?.length > 0) {
    media.edge_sidecar_to_children.edges.forEach((edge, idx) => {
      const node = edge.node;
      if (node.is_video && node.video_url) {
        items.push({
          url: node.video_url,
          type: "video",
          label: `Reel 视频 ${idx + 1} (MP4)`,
          ext: "mp4"
        });
      } else {
        const url = node.display_url || node.display_resources?.[node.display_resources.length - 1]?.src;
        if (url) {
          items.push({
            url,
            type: "image",
            label: `无水印原图 ${idx + 1}`,
            ext: "jpg"
          });
        }
      }
    });
  } else if (media.carousel_media?.length > 0) {
    // 2. 图集 carousel_media (REST API)
    media.carousel_media.forEach((it, idx) => {
      if (it.video_versions?.length > 0) {
        items.push({
          url: it.video_versions[0].url,
          type: "video",
          label: `Reel 视频 ${idx + 1} (MP4)`,
          ext: "mp4"
        });
      } else {
        const cands = it.image_versions2?.candidates || [];
        const url = cands[0]?.url;
        if (url) {
          items.push({
            url,
            type: "image",
            label: `无水印原图 ${idx + 1}`,
            ext: "jpg"
          });
        }
      }
    });
  } else if (media.is_video || media.video_versions?.length > 0 || media.video_url) {
    // 3. 单视频
    const url = media.video_url || media.video_versions?.[0]?.url;
    if (url) {
      items.push({
        url,
        type: "video",
        label: "Reel 视频 (MP4)",
        ext: "mp4"
      });
    }
  } else if (media.display_url || media.image_versions2?.candidates?.length > 0) {
    // 4. 单图片
    const url = media.display_url || media.image_versions2?.candidates?.[0]?.url;
    if (url) {
      items.push({
        url,
        type: "image",
        label: "无水印原图",
        ext: "jpg"
      });
    }
  }

  return {
    author,
    title: (author ? `@${author}: ` : "") + (title ? title.substring(0, 40) : "Instagram 帖子"),
    cover: items[0]?.url || "",
    items
  };
}

function parseInstagramFromHtml(html, shortcode) {
  const items = [];
  let author = "";
  let title = `Instagram 帖子 (${shortcode})`;

  // 1. 提取 edge_sidecar_to_children
  const sidecarMatch = html.match(/"edge_sidecar_to_children"\s*:\s*\{"edges"\s*:\s*(\[.*?\])\s*\}/);
  if (sidecarMatch) {
    try {
      const edges = JSON.parse(sidecarMatch[1]);
      edges.forEach((edge, idx) => {
        const node = edge.node;
        if (node.is_video && node.video_url) {
          items.push({
            url: node.video_url,
            type: "video",
            label: `Reel 视频 ${idx + 1} (MP4)`,
            ext: "mp4"
          });
        } else if (node.display_url) {
          items.push({
            url: node.display_url,
            type: "image",
            label: `无水印原图 ${idx + 1}`,
            ext: "jpg"
          });
        }
      });
    } catch (_) {}
  }

  // 2. 尝试从 HTML 内 script 提取 xdt_shortcode_media 或 carousel_media
  if (items.length === 0) {
    const xdtMatch = html.match(/"xdt_shortcode_media"\s*:\s*(\{.*?\})\s*,\s*"viewer"/);
    if (xdtMatch) {
      try {
        const media = JSON.parse(xdtMatch[1]);
        const parsed = parseInstagramMediaObj(media);
        if (parsed && parsed.items?.length > 0) {
          return parsed;
        }
      } catch (_) {}
    }
  }

  // 3. 提取所有 display_url
  if (items.length === 0) {
    const displayUrls = [];
    const re = /"display_url"\s*:\s*"([^"]+)"/g;
    let m;
    while ((m = re.exec(html)) !== null) {
      const u = m[1].replace(/\\u0026/g, "&").replace(/\\\//g, "/");
      if (!displayUrls.includes(u)) {
        displayUrls.push(u);
      }
    }
    if (displayUrls.length > 0) {
      displayUrls.forEach((u, idx) => {
        items.push({
          url: u,
          type: "image",
          label: `无水印原图 ${idx + 1}`,
          ext: "jpg"
        });
      });
    }
  }

  return {
    author,
    title,
    cover: items[0]?.url || "",
    items
  };
}

async function resolveInstagramPost(shortcode, inpageItems = []) {
  if (!shortcode) {
    return { items: inpageItems };
  }

  // 1. 请求 __a=1&__d=dis
  try {
    const apiUrl = `https://www.instagram.com/p/${shortcode}/?__a=1&__d=dis`;
    const resp = await fetch(apiUrl, {
      headers: {
        "X-IG-App-ID": "936619743392459",
        "X-Requested-With": "XMLHttpRequest",
        "Accept": "*/*"
      }
    });
    if (resp.ok) {
      const json = await resp.json();
      const media = json.graphql?.shortcode_media || json.items?.[0];
      if (media) {
        const parsed = parseInstagramMediaObj(media);
        if (parsed && parsed.items?.length > 0) {
          return parsed;
        }
      }
    }
  } catch (e) {
    console.warn("Instagram __a=1 fetch failed", e);
  }

  // 2. 请求页面完整 HTML 提取
  try {
    const pageUrl = `https://www.instagram.com/p/${shortcode}/`;
    const resp = await fetch(pageUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
      }
    });
    if (resp.ok) {
      const html = await resp.text();
      const parsed = parseInstagramFromHtml(html, shortcode);
      if (parsed && parsed.items?.length > 0) {
        return parsed;
      }
    }
  } catch (e) {
    console.warn("Instagram HTML fetch failed", e);
  }

  return { items: inpageItems };
}

// ── 四大主流平台专属深度解析器 ──────────────────────────────────────────────
async function resolveMediaForPlatform(targetUrl, inpageData) {
  const url = targetUrl.toLowerCase();

  // 1. X (Twitter)
  if (url.includes("x.com") || url.includes("twitter.com")) {
    const match = targetUrl.match(/\/status\/(\d+)/);
    if (match) {
      const tweetId = match[1];
      try {
        const resp = await fetch(`https://api.fxtwitter.com/status/${tweetId}`, {
          signal: AbortSignal.timeout(6000)
        });
        if (resp.ok) {
          const json = await resp.json();
          const tweet = json.tweet;
          if (tweet && tweet.media) {
            const videos = (tweet.media.videos || []).map(v => ({
              url: v.url,
              type: "video",
              label: `视频 (MP4)`,
              ext: "mp4"
            }));
            const images = (tweet.media.photos || []).map((p, idx) => ({
              url: p.url + (p.url.includes("?") ? "&name=orig" : "?name=orig"),
              type: "image",
              label: `原图 ${idx + 1}`,
              ext: "jpg"
            }));
            return {
              platform: "x",
              platformName: "X (Twitter)",
              title: tweet.text ? tweet.text.substring(0, 50) : "X_Tweet",
              author: tweet.author ? tweet.author.name : "",
              cover: tweet.media.photos?.[0]?.url || tweet.author?.avatar_url,
              items: [...videos, ...images]
            };
          }
        }
      } catch (e) {
        console.warn("FxTwitter fetch failed, falling back to inpage data", e);
      }
    }
  }

  // 2. YouTube
  if (url.includes("youtube.com") || url.includes("youtu.be")) {
    let videoId = null;
    if (url.includes("youtu.be")) {
      videoId = targetUrl.split("youtu.be/")[1]?.split("?")[0];
    } else if (url.includes("/shorts/")) {
      videoId = targetUrl.split("/shorts/")[1]?.split("?")[0];
    } else {
      const m = targetUrl.match(/[?&]v=([^&]+)/);
      videoId = m ? m[1] : null;
    }

    const title = inpageData?.title || "YouTube_Video";
    const cover = videoId ? `https://i.ytimg.com/vi/${videoId}/maxresdefault.jpg` : null;

    // 尝试调用 cobalt tools 解析 1080p MP4
    const items = [];
    try {
      const cobResp = await fetch("https://api.cobalt.tools", {
        method: "POST",
        headers: {
          "Accept": "application/json",
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          url: targetUrl,
          videoQuality: "1080"
        }),
        signal: AbortSignal.timeout(6000)
      });
      if (cobResp.ok) {
        const cJson = await cobResp.json();
        if (cJson.url) {
          items.push({
            url: cJson.url,
            type: "video",
            label: "高清视频 (1080p MP4)",
            ext: "mp4"
          });
        }
      }
    } catch (_) {}

    // 音频提取通道
    try {
      const audioResp = await fetch("https://api.cobalt.tools", {
        method: "POST",
        headers: { "Accept": "application/json", "Content-Type": "application/json" },
        body: JSON.stringify({ url: targetUrl, downloadMode: "audio" }),
        signal: AbortSignal.timeout(4000)
      });
      if (audioResp.ok) {
        const aJson = await audioResp.json();
        if (aJson.url) {
          items.push({
            url: aJson.url,
            type: "audio",
            label: "纯音频 (MP3)",
            ext: "mp3"
          });
        }
      }
    } catch (_) {}

    return {
      platform: "youtube",
      platformName: "YouTube",
      title,
      cover,
      items
    };
  }

  // 3. Instagram 专属深度图集与视频解析
  if (url.includes("instagram.com")) {
    const match = targetUrl.match(/\/(p|reel|reels)\/([a-zA-Z0-9_-]+)/);
    const shortcode = match ? match[2] : (inpageData?.id || null);
    if (shortcode) {
      const igRes = await resolveInstagramPost(shortcode, inpageData?.items || []);
      if (igRes && igRes.items && igRes.items.length > 0) {
        return {
          platform: "instagram",
          platformName: "Instagram",
          title: igRes.title || inpageData?.title || "Instagram 作品",
          author: igRes.author || inpageData?.author || "Instagram",
          cover: igRes.cover || inpageData?.cover || igRes.items[0]?.url,
          items: igRes.items
        };
      }
    }
  }

  // 4. 小红书与其他平台（使用 Inpage 采集到的 DOM/媒体直链）
  if (inpageData && (inpageData.videos.length > 0 || inpageData.images.length > 0)) {
    const items = [];
    inpageData.videos.forEach((v, idx) => {
      items.push({
        url: v,
        type: "video",
        label: `视频 ${idx + 1} (MP4)`,
        ext: "mp4"
      });
    });
    inpageData.images.forEach((img, idx) => {
      items.push({
        url: img,
        type: "image",
        label: `无水印原图 ${idx + 1}`,
        ext: "jpg"
      });
    });

    return {
      platform: inpageData.platform,
      platformName: inpageData.name,
      title: inpageData.title || `${inpageData.name}_Media`,
      cover: inpageData.images[0] || null,
      items
    };
  }

  return null;
}

// 消息调度中心
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    try {
      switch (msg.action) {
        case "CHECK_COOKIES": {
          const res = await checkAllPlatformCookies();
          sendResponse({ success: true, data: res });
          break;
        }

        case "SYNC_COOKIES_TO_PHONE": {
          const cookies = await checkAllPlatformCookies();
          const payload = {};
          for (const [k, v] of Object.entries(cookies)) {
            if (v.loggedIn && v.cookie) {
              payload[k] = v.cookie;
            }
          }
          if (Object.keys(payload).length === 0) {
            throw new Error("电脑浏览器尚未检测到 Instagram/小红书/X/YouTube 等有效登录态");
          }
          const res = await fetchFromPhone("/api/cookies", {
            method: "POST",
            body: JSON.stringify({ cookies: payload })
          });
          sendResponse({ success: true, data: res });
          break;
        }

        case "SYNC_SINGLE_COOKIE": {
          const { platform } = msg;
          const info = await checkPlatformCookie(platform);
          if (!info.loggedIn || !info.cookie) {
            throw new Error(`${info.name} 在当前电脑浏览器中未检测到有效登录态`);
          }
          const res = await fetchFromPhone("/api/cookies", {
            method: "POST",
            body: JSON.stringify({ platform, cookie: info.cookie })
          });
          sendResponse({ success: true, data: res });
          break;
        }

        case "RESOLVE_MEDIA": {
          const { url, inpageData } = msg;
          const media = await resolveMediaForPlatform(url, inpageData);
          sendResponse({ success: true, data: media });
          break;
        }

        case "RESOLVE_INSTAGRAM_POST": {
          const { shortcode, inpageItems = [] } = msg;
          const res = await resolveInstagramPost(shortcode, inpageItems);
          sendResponse({ success: true, data: res });
          break;
        }

        case "GET_DOWNLOAD_CONFIG": {
          const cfg = await getDownloadConfig();
          sendResponse({ success: true, data: cfg });
          break;
        }

        case "DOWNLOAD_MEDIA_PC": {
          const { url, filename, platform, forceSaveAs } = msg;
          const res = await downloadToPc(url, filename, platform, forceSaveAs);
          sendResponse({ success: true, data: res });
          break;
        }

        case "DOWNLOAD_BATCH_PC": {
          const { items, baseTitle, platform } = msg;
          const results = [];
          let savedFolder = "";
          for (let i = 0; i < items.length; i++) {
            const item = items[i];
            const name = `${baseTitle}_${i + 1}.${item.ext || "jpg"}`;
            try {
              // 批量图集下载强制避免弹窗轰炸，安全存入指定目录
              const r = await downloadToPc(item.url, name, platform, false);
              savedFolder = r.folder;
              results.push(r);
            } catch (e) {
              console.warn("Batch item download failed", item.url, e);
            }
          }
          sendResponse({ success: true, count: results.length, folder: savedFolder });
          break;
        }

        case "DOWNLOAD_CURRENT_TAB_PC": {
          const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
          if (!tab || !tab.url) throw new Error("未检测到当前网页标签");
          const media = await resolveMediaForPlatform(tab.url, msg.inpageData);
          if (!media || !media.items || media.items.length === 0) {
            throw new Error("当前网页未检测到可直接下载的媒体流");
          }
          const item = media.items[0];
          const filename = `${(media.title || "ClipDown_Media").substring(0, 40)}.${item.ext || "mp4"}`;
          const res = await downloadToPc(item.url, filename, media.platform);
          sendResponse({ success: true, data: res });
          break;
        }

        case "TEST_CONNECTION": {
          const res = await fetchFromPhone("/api/status", { method: "GET" });
          sendResponse({ success: true, data: res });
          break;
        }

        case "PULL_TASKS": {
          const res = await fetchFromPhone("/api/tasks", { method: "GET" });
          sendResponse({ success: true, data: res });
          break;
        }

        case "PUSH_TASKS": {
          const res = await fetchFromPhone("/api/tasks", {
            method: "POST",
            body: JSON.stringify({ tasks: msg.tasks || [] })
          });
          sendResponse({ success: true, data: res });
          break;
        }

        case "ENQUEUE_URL": {
          const res = await fetchFromPhone("/api/enqueue", {
            method: "POST",
            body: JSON.stringify({ url: msg.url })
          });
          sendResponse({ success: true, data: res });
          break;
        }

        case "PUSH_BATCH_TO_PHONE": {
          const posts = msg.posts || [];
          const res = await fetchFromPhone("/api/enqueue_batch", {
            method: "POST",
            body: JSON.stringify({
              urls: posts.map(p => p.url).filter(Boolean),
              items: posts.map(p => ({
                url: p.url,
                title: p.title || p.text || p.author,
                coverUrl: p.coverUrl
              }))
            })
          });
          sendResponse({ success: true, data: res });
          break;
        }

        case "DOWNLOAD_PAGE_BATCH": {
          const { posts = [], platform = "Batch", subfolderName } = msg;
          const results = [];
          let savedFolder = "";
          for (let i = 0; i < posts.length; i++) {
            const post = posts[i];
            const authorPrefix = (post.author || "media").replace(/[^a-zA-Z0-9_\u4e00-\u9fa5]/g, "").slice(0, 20);
            const postItems = post.items || [];
            if (postItems.length > 0) {
              for (let j = 0; j < postItems.length; j++) {
                const it = postItems[j];
                const filename = `${authorPrefix}_${post.shortcode || post.id || i}_${j + 1}.${it.ext || "jpg"}`;
                try {
                  const r = await downloadToPc(it.url, filename, subfolderName || platform, false);
                  savedFolder = r.folder;
                  results.push(r);
                } catch (e) {
                  console.warn("Batch item download failed", it.url, e);
                }
              }
            } else if (post.coverUrl) {
              const filename = `${authorPrefix}_${post.shortcode || post.id || i}.jpg`;
              try {
                const r = await downloadToPc(post.coverUrl, filename, subfolderName || platform, false);
                savedFolder = r.folder;
                results.push(r);
              } catch (e) {
                console.warn("Cover download failed", post.coverUrl, e);
              }
            }
          }
          sendResponse({ success: true, count: results.length, folder: savedFolder });
          break;
        }

        default:
          sendResponse({ success: false, error: "未知操作" });
      }
    } catch (err) {
      console.error("Action error:", msg.action, err);
      sendResponse({ success: false, error: err.message || String(err) });
    }
  })();
  return true;
});
