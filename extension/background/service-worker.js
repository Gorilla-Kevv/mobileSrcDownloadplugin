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

// 获取配对设置
async function getPairingConfig() {
  const data = await chrome.storage.local.get(["phoneUrl", "phonePin", "autoSync"]);
  return {
    phoneUrl: (data.phoneUrl || "http://192.168.1.108:8765").replace(/\/+$/, ""),
    phonePin: data.phonePin || "",
    autoSync: !!data.autoSync
  };
}

// 局域网发送 HTTP 请求到手机
async function fetchFromPhone(endpoint, options = {}) {
  const { phoneUrl, phonePin } = await getPairingConfig();
  const url = `${phoneUrl}${endpoint}`;

  const headers = {
    "Content-Type": "application/json",
    "X-Sync-PIN": phonePin,
    ...(options.headers || {})
  };

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
}

// ── PC 电脑端原生直接下载引擎 ──────────────────────────────────────────────
async function downloadToPc(url, filename, folder = "ClipDown") {
  const cleanName = filename.replace(/[\\/:*?"<>|]/g, "_").trim();
  const fullPath = `${folder}/${cleanName}`;

  return new Promise((resolve, reject) => {
    chrome.downloads.download({
      url,
      filename: fullPath,
      conflictAction: "uniquify"
    }, (downloadId) => {
      if (chrome.runtime.lastError) {
        reject(new Error(chrome.runtime.lastError.message));
      } else {
        resolve({ downloadId, filename: fullPath });
      }
    });
  });
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

  // 3. Instagram & 小红书（使用 Inpage 采集到的 DOM/媒体直链）
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

        case "DOWNLOAD_MEDIA_PC": {
          const { url, filename, platform } = msg;
          const folder = platform ? `ClipDown/${platform}` : "ClipDown";
          const res = await downloadToPc(url, filename, folder);
          sendResponse({ success: true, data: res });
          break;
        }

        case "DOWNLOAD_BATCH_PC": {
          const { items, baseTitle, platform } = msg;
          const folder = platform ? `ClipDown/${platform}` : "ClipDown";
          const results = [];
          for (let i = 0; i < items.length; i++) {
            const item = items[i];
            const name = `${baseTitle}_${i + 1}.${item.ext || "jpg"}`;
            try {
              const r = await downloadToPc(item.url, name, folder);
              results.push(r);
            } catch (e) {
              console.warn("Batch item download failed", item.url, e);
            }
          }
          sendResponse({ success: true, count: results.length });
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
