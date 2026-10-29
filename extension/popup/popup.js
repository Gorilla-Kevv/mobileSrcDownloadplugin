/**
 * ClipDown 跨端助手 - Popup 交互逻辑 (深度优化 X、Instagram、YouTube、小红书)
 */

document.addEventListener("DOMContentLoaded", async () => {
  // ── DOM 元素获取 ───────────────────────────────────────────────────────────
  const tabs = document.querySelectorAll(".tab-btn");
  const tabPanes = document.querySelectorAll(".tab-pane");
  const connectionPill = document.getElementById("connection-pill");
  const footerStatus = document.getElementById("footer-status");

  // 媒体嗅探与直接下载元素
  const mediaPlatformBadge = document.getElementById("media-platform-badge");
  const mediaTypeBadge = document.getElementById("media-type-badge");
  const mediaCoverImg = document.getElementById("media-cover-img");
  const mediaTitleText = document.getElementById("media-title-text");
  const mediaUrlText = document.getElementById("media-url-text");
  const mediaItemsList = document.getElementById("media-items-list");
  const btnPcDownloadPrimary = document.getElementById("btn-pc-download-primary");
  const btnBatchDownloadImages = document.getElementById("btn-batch-download-images");
  const btnPushToMobile = document.getElementById("btn-push-to-mobile");
  const btnCopyMediaUrl = document.getElementById("btn-copy-media-url");
  const manualUrlInput = document.getElementById("manual-url-input");
  const btnManualResolve = document.getElementById("btn-manual-resolve");

  // Cookie 共享页元素
  const btnSyncAllCookies = document.getElementById("btn-sync-all-cookies");
  const btnQrSync = document.getElementById("btn-qr-sync");
  const btnCopyDeeplink = document.getElementById("btn-copy-deeplink");

  // 任务记录页元素
  const btnPullTasks = document.getElementById("btn-pull-tasks");
  const tasksStatsText = document.getElementById("tasks-stats-text");
  const tasksSearchInput = document.getElementById("tasks-search-input");
  const tasksListContainer = document.getElementById("tasks-list-container");

  // 设置页元素
  const inputPhoneUrl = document.getElementById("input-phone-url");
  const inputPhonePin = document.getElementById("input-phone-pin");
  const btnTestSaveSettings = document.getElementById("btn-test-save-settings");
  const settingsStatusBox = document.getElementById("settings-status-box");

  // 扫码弹窗
  const modalQr = document.getElementById("modal-qr");
  const btnCloseQr = document.getElementById("btn-close-qr");
  const qrPlatformSelect = document.getElementById("qr-platform-select");
  const qrcodeContainer = document.getElementById("qrcode-container");

  let currentResolvedMedia = null;
  let allPlatformCookies = {};
  let cachedTasks = [];

  // ── 标签页切换 ─────────────────────────────────────────────────────────────
  tabs.forEach(tab => {
    tab.addEventListener("click", () => {
      tabs.forEach(t => t.classList.remove("active"));
      tabPanes.forEach(p => p.classList.remove("active"));

      tab.classList.add("active");
      const targetPane = document.getElementById(tab.dataset.tab);
      if (targetPane) targetPane.classList.add("active");
    });
  });

  // ── 初始化配置加载 ─────────────────────────────────────────────────────────
  const stored = await chrome.storage.local.get(["phoneUrl", "phonePin", "cached_tasks"]);
  inputPhoneUrl.value = stored.phoneUrl || "http://192.168.1.108:8765";
  inputPhonePin.value = stored.phonePin || "";
  cachedTasks = stored.cached_tasks || [];

  // 初始化检查
  testConnection(false);
  refreshCookiesState();
  renderTasks(cachedTasks);
  sniffCurrentTabMedia();

  // ── 核心：X / Instagram / YouTube / 小红书 深度媒体嗅探与下载 ───────────────
  async function sniffCurrentTabMedia() {
    try {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      if (!tab || !tab.url) return;

      mediaUrlText.textContent = tab.url;
      mediaTitleText.textContent = tab.title || "正在嗅探网页媒体...";

      // 向当前标签页 content script 发送嗅探指令
      let inpageData = null;
      try {
        const inpageResp = await chrome.tabs.sendMessage(tab.id, { action: "EXTRACT_INPAGE_MEDIA" });
        if (inpageResp && inpageResp.success) {
          inpageData = inpageResp.data;
        }
      } catch (_) {
        // 如果页面未加载 content script，继续通过 URL 解析
      }

      // 请求后台针对该平台进行深度解析
      const resolveResp = await chrome.runtime.sendMessage({
        action: "RESOLVE_MEDIA",
        url: tab.url,
        inpageData
      });

      if (resolveResp && resolveResp.success && resolveResp.data) {
        currentResolvedMedia = resolveResp.data;
        renderResolvedMedia(currentResolvedMedia);
      } else if (inpageData) {
        currentResolvedMedia = inpageData;
        renderResolvedMedia(currentResolvedMedia);
      } else {
        renderUnknownMedia(tab);
      }
    } catch (e) {
      console.warn("Sniff error:", e);
    }
  }

  function renderResolvedMedia(media) {
    mediaPlatformBadge.className = "pill pill-brand";
    mediaPlatformBadge.textContent = media.platformName || media.name || "媒体已识别";

    const hasVideo = media.items && media.items.some(x => x.type === "video");
    const imageCount = media.items ? media.items.filter(x => x.type === "image").length : 0;

    if (hasVideo && imageCount > 0) {
      mediaTypeBadge.textContent = `视频 + ${imageCount} 张图集`;
    } else if (hasVideo) {
      mediaTypeBadge.textContent = "🎬 视频媒体";
    } else if (imageCount > 0) {
      mediaTypeBadge.textContent = `🖼️ ${imageCount} 张高清原图`;
    } else {
      mediaTypeBadge.textContent = "在线媒体";
    }

    mediaTitleText.textContent = media.title || "未命名媒体";
    if (media.cover) {
      mediaCoverImg.src = media.cover;
    } else {
      mediaCoverImg.src = "../icons/icon48.png";
    }

    // 渲染媒体分项列表
    if (media.items && media.items.length > 0) {
      mediaItemsList.innerHTML = media.items.map((item, idx) => `
        <div class="media-item-row">
          <span class="item-label">${item.label}</span>
          <div class="item-actions">
            <button class="btn btn-sm btn-primary btn-item-dl" data-idx="${idx}">下载</button>
            <button class="btn btn-sm btn-secondary btn-item-copy" data-url="${item.url}">复制</button>
          </div>
        </div>
      `).join("");

      // 绑定单项下载与复制
      document.querySelectorAll(".btn-item-dl").forEach(btn => {
        btn.addEventListener("click", () => {
          const idx = parseInt(btn.dataset.idx, 10);
          const item = media.items[idx];
          downloadSingleItem(item, media.platform);
        });
      });

      document.querySelectorAll(".btn-item-copy").forEach(btn => {
        btn.addEventListener("click", () => {
          navigator.clipboard.writeText(btn.dataset.url);
          alert("已复制媒体直链！");
        });
      });
    } else {
      mediaItemsList.innerHTML = `<div class="item-placeholder">未发现直接下载流，支持一键推送到手机解析</div>`;
    }

    // 图集批量下载按钮显隐
    if (imageCount > 1) {
      btnBatchDownloadImages.classList.remove("hidden");
      btnBatchDownloadImages.textContent = `📦 批量下载全部 ${imageCount} 张原图`;
    } else {
      btnBatchDownloadImages.classList.add("hidden");
    }

    btnPcDownloadPrimary.disabled = !(media.items && media.items.length > 0);
  }

  function renderUnknownMedia(tab) {
    mediaPlatformBadge.className = "pill pill-neutral";
    mediaPlatformBadge.textContent = "通用网页";
    mediaTypeBadge.textContent = "未发现直接媒体流";
    btnBatchDownloadImages.classList.add("hidden");
    mediaItemsList.innerHTML = `
      <div class="item-placeholder">
        未在当前页嗅探到直链，可点击下方「🚀 推送到手机」通过移动端内核深度提取
      </div>
    `;
  }

  // 电脑端直接下载主按钮
  btnPcDownloadPrimary.addEventListener("click", async () => {
    if (!currentResolvedMedia || !currentResolvedMedia.items || currentResolvedMedia.items.length === 0) {
      alert("当前页面未解析到可下载的媒体直链");
      return;
    }

    const firstItem = currentResolvedMedia.items[0];
    const imageCount = currentResolvedMedia.items.filter(x => x.type === "image").length;

    // 如果是多图图集，默认触发批量下载
    if (imageCount > 1 && !currentResolvedMedia.items.some(x => x.type === "video")) {
      downloadBatchImages();
      return;
    }

    downloadSingleItem(firstItem, currentResolvedMedia.platform);
  });

  // 批量下载所有原图
  btnBatchDownloadImages.addEventListener("click", () => {
    downloadBatchImages();
  });

  async function downloadBatchImages() {
    if (!currentResolvedMedia) return;
    const images = currentResolvedMedia.items.filter(x => x.type === "image");
    if (images.length === 0) return;

    btnBatchDownloadImages.disabled = true;
    btnBatchDownloadImages.textContent = "正在批量下载中...";

    const res = await chrome.runtime.sendMessage({
      action: "DOWNLOAD_BATCH_PC",
      items: images,
      baseTitle: currentResolvedMedia.title || "ClipDown_Image",
      platform: currentResolvedMedia.platform
    });

    btnBatchDownloadImages.disabled = false;
    btnBatchDownloadImages.textContent = `📦 批量下载全部 ${images.length} 张原图`;

    if (res && res.success) {
      alert(`🎉 成功启动下载全部 ${res.count} 张高清原图！\n文件保存在电脑 Downloads/ClipDown 目录。`);
    } else {
      alert("下载遇到问题，请重试");
    }
  }

  async function downloadSingleItem(item, platform) {
    const title = (currentResolvedMedia?.title || "ClipDown_Media").substring(0, 40);
    const filename = `${title}.${item.ext || "mp4"}`;

    btnPcDownloadPrimary.disabled = true;
    btnPcDownloadPrimary.textContent = "正在调用电脑下载器...";

    try {
      const res = await chrome.runtime.sendMessage({
        action: "DOWNLOAD_MEDIA_PC",
        url: item.url,
        filename,
        platform
      });

      if (res && res.success) {
        alert(`✅ 已在电脑端开始下载: ${filename}\n保存于 Downloads/ClipDown 目录。`);
      } else {
        alert(`❌ 下载失败: ${res ? res.error : "未知错误"}`);
      }
    } catch (e) {
      alert(`❌ 下载异常: ${e.message}`);
    } finally {
      btnPcDownloadPrimary.disabled = false;
      btnPcDownloadPrimary.textContent = "💻 一键下载到电脑 (Downloads)";
    }
  }

  // 推送到手机下载
  btnPushToMobile.addEventListener("click", async () => {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab || !tab.url) return;

    btnPushToMobile.disabled = true;
    btnPushToMobile.textContent = "正在推送...";

    try {
      const res = await chrome.runtime.sendMessage({
        action: "ENQUEUE_URL",
        url: tab.url
      });
      if (res && res.success) {
        alert("🚀 已成功推送到手机！手机悬浮球与下载引擎已自动启动识别与下载。");
      } else {
        alert(`❌ 推送失败: ${res ? res.error : "请先在「配对设置」连接手机"}`);
      }
    } catch (e) {
      alert(`❌ 推送异常: ${e.message}`);
    } finally {
      btnPushToMobile.disabled = false;
      btnPushToMobile.textContent = "🚀 推送到手机下载";
    }
  });

  // 复制直链
  btnCopyMediaUrl.addEventListener("click", () => {
    if (currentResolvedMedia?.items?.[0]?.url) {
      navigator.clipboard.writeText(currentResolvedMedia.items[0].url);
      alert("✅ 已复制媒体直链！");
    } else {
      const [tab] = chrome.tabs.query({ active: true, currentWindow: true });
      if (mediaUrlText.textContent) {
        navigator.clipboard.writeText(mediaUrlText.textContent);
        alert("✅ 已复制当前网页链接！");
      }
    }
  });

  // 手动解析栏
  btnManualResolve.addEventListener("click", async () => {
    const rawUrl = manualUrlInput.value.trim();
    if (!rawUrl) {
      alert("请输入要解析的帖子链接");
      return;
    }
    mediaUrlText.textContent = rawUrl;
    mediaTitleText.textContent = "正在解析手动输入的链接...";

    const res = await chrome.runtime.sendMessage({
      action: "RESOLVE_MEDIA",
      url: rawUrl
    });

    if (res && res.success && res.data) {
      currentResolvedMedia = res.data;
      renderResolvedMedia(currentResolvedMedia);
    } else {
      alert("未能解析出直链，支持推送到手机下载");
    }
  });

  // ── 登录态共享逻辑（X、Instagram、小红书、YouTube、Facebook）────────────
  async function refreshCookiesState() {
    try {
      const resp = await chrome.runtime.sendMessage({ action: "CHECK_COOKIES" });
      if (!resp || !resp.success) return;

      allPlatformCookies = resp.data;
      for (const [key, info] of Object.entries(allPlatformCookies)) {
        const row = document.querySelector(`.platform-row[data-platform="${key}"]`);
        if (!row) continue;

        const pill = row.querySelector(".platform-status .pill");
        const btnSync = row.querySelector(".btn-sync");

        if (info.loggedIn) {
          pill.className = "pill pill-online";
          pill.textContent = "已登录";
          btnSync.disabled = false;
        } else {
          pill.className = "pill pill-neutral";
          pill.textContent = "未登录";
          btnSync.disabled = true;
        }
      }
    } catch (err) {
      console.warn("Refresh cookies error:", err);
    }
  }

  // 单平台一键同步
  document.querySelectorAll(".btn-sync").forEach(btn => {
    btn.addEventListener("click", async () => {
      const platform = btn.dataset.platform;
      btn.disabled = true;
      btn.textContent = "同步中...";
      try {
        const res = await chrome.runtime.sendMessage({
          action: "SYNC_SINGLE_COOKIE",
          platform
        });
        if (res && res.success) {
          alert(`✅ ${platform} Cookie 已成功同步至手机！\n已自动重置风控冷却。`);
        } else {
          alert(`❌ 同步失败: ${res ? res.error : "未知错误"}`);
        }
      } catch (err) {
        alert(`❌ 同步异常: ${err.message}`);
      } finally {
        btn.disabled = false;
        btn.textContent = "同步";
      }
    });
  });

  // 一键同步全部已登录平台的 Cookie
  btnSyncAllCookies.addEventListener("click", async () => {
    btnSyncAllCookies.disabled = true;
    btnSyncAllCookies.textContent = "正在灌入 Cookie 到手机...";
    try {
      const res = await chrome.runtime.sendMessage({ action: "SYNC_COOKIES_TO_PHONE" });
      if (res && res.success) {
        const msg = res.data && res.data.message ? res.data.message : "登录态同步成功";
        alert(`🎉 恭喜！${msg}\n手机 ClipDown 已成功获得电脑端完整登录态，可直接解析原画质！`);
      } else {
        alert(`❌ 同步失败: ${res ? res.error : "请先确保电脑已登录平台且手机局域网服务已启动"}`);
      }
    } catch (err) {
      alert(`❌ 网络错误: ${err.message}`);
    } finally {
      btnSyncAllCookies.disabled = false;
      btnSyncAllCookies.textContent = "⚡ 一键同步所有 Cookie 到手机";
    }
  });

  // 离线扫码同步
  btnQrSync.addEventListener("click", () => {
    modalQr.classList.remove("hidden");
    updateQrCode();
  });

  btnCloseQr.addEventListener("click", () => {
    modalQr.classList.add("hidden");
  });

  qrPlatformSelect.addEventListener("change", () => {
    updateQrCode();
  });

  function updateQrCode() {
    const platform = qrPlatformSelect.value;
    const info = allPlatformCookies[platform];
    if (!info || !info.cookie) {
      qrcodeContainer.innerHTML = `<div style="color:#666;font-size:12px;padding:20px;">当前电脑浏览器未检测到 ${platform} 登录态，请先登录</div>`;
      return;
    }
    const deepLink = `clipdown://cookie?platform=${platform}&data=${encodeURIComponent(info.cookie)}`;
    qrcodeContainer.innerHTML = "";
    new QRCode(qrcodeContainer, {
      text: deepLink,
      width: 180,
      height: 180,
      colorDark: "#09060E",
      colorLight: "#FFFFFF"
    });
  }

  // 离线 DeepLink 复制
  btnCopyDeeplink.addEventListener("click", async () => {
    const defaultPlat = "x";
    const info = allPlatformCookies[defaultPlat] || Object.values(allPlatformCookies).find(x => x.loggedIn);
    if (!info || !info.cookie) {
      alert("当前电脑浏览器未检测到已登录的平台");
      return;
    }
    const deepLink = `clipdown://cookie?platform=${info.platform}&data=${encodeURIComponent(info.cookie)}`;
    await navigator.clipboard.writeText(deepLink);
    alert(`已复制 ${info.name} 的一键同步协议链接：\n\n${deepLink.substring(0, 80)}...\n\n发到微信/QQ后点击即可一键导入手机 ClipDown！`);
  });

  // ── 任务记录双向同步 ───────────────────────────────────────────────────────
  btnPullTasks.addEventListener("click", async () => {
    btnPullTasks.disabled = true;
    btnPullTasks.textContent = "同步中...";
    try {
      const resp = await chrome.runtime.sendMessage({ action: "PULL_TASKS" });
      if (resp && resp.success) {
        const phoneTasks = (resp.data && resp.data.tasks) || [];
        const map = new Map();
        for (const t of cachedTasks) map.set(t.id, t);
        for (const t of phoneTasks) map.set(t.id, t);
        cachedTasks = Array.from(map.values()).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));

        await chrome.storage.local.set({ cached_tasks: cachedTasks });
        renderTasks(cachedTasks);
        alert(`✅ 同步完成！共拉取合并 ${cachedTasks.length} 条下载记录`);
      } else {
        alert(`❌ 同步失败: ${resp ? resp.error : "请先连接手机"}`);
      }
    } catch (err) {
      alert(`❌ 同步异常: ${err.message}`);
    } finally {
      btnPullTasks.disabled = false;
      btnPullTasks.textContent = "🔄 双向同步";
    }
  });

  tasksSearchInput.addEventListener("input", () => {
    const q = tasksSearchInput.value.trim().toLowerCase();
    if (!q) {
      renderTasks(cachedTasks);
      return;
    }
    const filtered = cachedTasks.filter(t =>
      (t.title && t.title.toLowerCase().includes(q)) ||
      (t.platformId && t.platformId.toLowerCase().includes(q)) ||
      (t.url && t.url.toLowerCase().includes(q))
    );
    renderTasks(filtered);
  });

  function renderTasks(list) {
    tasksStatsText.textContent = `📱 同步记录: ${list.length} 条`;
    if (!list || list.length === 0) {
      tasksListContainer.innerHTML = `
        <div class="empty-state">
          <div class="empty-icon">📭</div>
          <div class="empty-text">暂无同步记录，点击上方「双向同步」即可拉取手机历史任务</div>
        </div>
      `;
      return;
    }

    tasksListContainer.innerHTML = list.map(t => {
      const cover = t.coverUrl || "../icons/icon48.png";
      const statusText = t.status === "COMPLETED" ? "已完成" : t.status === "DOWNLOADING" ? "下载中" : "已入库";
      const statusPillClass = t.status === "COMPLETED" ? "pill-online" : "pill-neutral";
      const dateStr = t.createdAt ? new Date(t.createdAt).toLocaleDateString() : "";
      return `
        <div class="task-item">
          <img src="${cover}" class="task-thumb" onerror="this.src='../icons/icon48.png'" />
          <div class="task-content">
            <div class="task-title" title="${t.title}">${t.title || "未命名媒体"}</div>
            <div class="task-meta">
              <span class="pill ${statusPillClass}">${statusText}</span>
              <span style="font-size:10px;color:#94A3B8;">${t.platformId || "通用"} · ${dateStr}</span>
              ${t.sourceUrl ? `<a href="${t.sourceUrl}" target="_blank" style="font-size:10px;color:#8B5CF6;text-decoration:none;">查看原帖</a>` : ""}
            </div>
          </div>
        </div>
      `;
    }).join("");
  }

  // ── 配对设置 ───────────────────────────────────────────────────────────────
  btnTestSaveSettings.addEventListener("click", async () => {
    const url = inputPhoneUrl.value.trim().replace(/\/+$/, "");
    const pin = inputPhonePin.value.trim();

    if (!url) {
      showStatusBox("请输入手机服务地址", "error");
      return;
    }
    if (pin.length !== 6) {
      showStatusBox("请输入手机屏幕显示的 6 位 PIN 码", "error");
      return;
    }

    await chrome.storage.local.set({ phoneUrl: url, phonePin: pin });
    showStatusBox("正在探测手机连接...", "info");
    await testConnection(true);
  });

  async function testConnection(showToast) {
    try {
      const resp = await chrome.runtime.sendMessage({ action: "TEST_CONNECTION" });
      if (resp && resp.success) {
        const info = resp.data;
        updateConnectionPill(true, info.deviceName || "手机");
        if (showToast) {
          showStatusBox(`✅ 连接成功！已连接到 ${info.deviceName}（版本 ${info.appVersion}）`, "success");
        }
        footerStatus.textContent = `已连接: ${info.deviceName}`;
      } else {
        updateConnectionPill(false);
        if (showToast) {
          showStatusBox(`❌ 连接失败: ${resp ? resp.error : "无法访问手机"}，请检查 IP 与 PIN`, "error");
        }
        footerStatus.textContent = "未连接手机";
      }
    } catch (err) {
      updateConnectionPill(false);
      if (showToast) {
        showStatusBox(`❌ 网络异常: ${err.message}`, "error");
      }
      footerStatus.textContent = "连接异常";
    }
  }

  function updateConnectionPill(online, deviceName) {
    if (online) {
      connectionPill.className = "pill pill-online";
      connectionPill.innerHTML = `<span class="status-dot"></span><span class="status-label">${deviceName || "已连接"}</span>`;
    } else {
      connectionPill.className = "pill pill-offline";
      connectionPill.innerHTML = `<span class="status-dot"></span><span class="status-label">未连接手机</span>`;
    }
  }

  function showStatusBox(msg, type) {
    settingsStatusBox.className = `status-box ${type}`;
    settingsStatusBox.textContent = msg;
    settingsStatusBox.classList.remove("hidden");
  }
});
