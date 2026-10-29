/**
 * ClipDown 跨端助手 - Background Service Worker
 * 负责平台 Cookie 读取、与手机端局域网通信、跨域请求调度。
 */

const PLATFORMS = {
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
  x: {
    name: "X (Twitter)",
    domains: [".x.com", "x.com", ".twitter.com", "twitter.com"],
    requiredTokens: ["auth_token="],
    urlPattern: /x\.com|twitter\.com/i
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
            throw new Error("电脑浏览器尚未登录任何支持的平台（Instagram/小红书等）");
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
  return true; // 保持异步响应通道
});
