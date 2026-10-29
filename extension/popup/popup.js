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

  // 全页面采集引导横幅元素
  const pageHarvesterBanner = document.getElementById("page-harvester-banner");
  const harvesterBannerTitle = document.getElementById("harvester-banner-title");
  const btnTriggerPageHarvest = document.getElementById("btn-trigger-page-harvest");

  // 下载路径指示与快速更改元素
  const currentDownloadPathDisplay = document.getElementById("current-download-path-display");
  const btnQuickConfigPath = document.getElementById("btn-quick-config-path");

  // 下载目录设置卡片元素
  const inputDownloadSubfolder = document.getElementById("input-download-subfolder");
  const previewPathText = document.getElementById("preview-path-text");
  const checkGroupByPlatform = document.getElementById("check-group-by-platform");
  const checkPromptSaveas = document.getElementById("check-prompt-saveas");
  const btnSaveDownloadSettings = document.getElementById("btn-save-download-settings");
  const downloadSettingsStatus = document.getElementById("download-settings-status");
  const btnOpenEdgeSettings = document.getElementById("btn-open-edge-settings");
  const btnOpenChromeSettings = document.getElementById("btn-open-chrome-settings");

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

  // 快捷免输配对元素
  const btnScanQr = document.getElementById("btn-scan-qr");
  const btnPastePairing = document.getElementById("btn-paste-pairing");
  const btnAutoDiscover = document.getElementById("btn-auto-discover");
  const modalScanner = document.getElementById("modal-scanner");
  const btnCloseScanner = document.getElementById("btn-close-scanner");
  const scannerVideo = document.getElementById("scanner-video");
  const scannerCanvas = document.getElementById("scanner-canvas");
  const scannerCameraStatus = document.getElementById("scanner-camera-status");
  const scannerStatusText = document.getElementById("scanner-status-text");
  const btnUploadQrImg = document.getElementById("btn-upload-qr-img");
  const inputQrFile = document.getElementById("input-qr-file");

  // 离线 Cookie 二维码弹窗
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
  const stored = await chrome.storage.local.get([
    "phoneUrl",
    "phonePin",
    "cached_tasks",
    "downloadSubfolder",
    "groupByPlatform",
    "promptSaveAs"
  ]);

  inputPhoneUrl.value = stored.phoneUrl || "http://192.168.1.108:8765";
  inputPhonePin.value = stored.phonePin || "";
  cachedTasks = stored.cached_tasks || [];

  // 下载目录设置初始化
  inputDownloadSubfolder.value = stored.downloadSubfolder !== undefined ? stored.downloadSubfolder : "ClipDown";
  checkGroupByPlatform.checked = stored.groupByPlatform !== false;
  checkPromptSaveas.checked = !!stored.promptSaveAs;

  function updateDownloadPreview() {
    const sub = (inputDownloadSubfolder.value || "").trim().replace(/^[\\/]+|[\\/]+$/g, "");
    const group = checkGroupByPlatform.checked;
    const prompt = checkPromptSaveas.checked;

    const parts = ["此电脑 > 下载"];
    if (sub) {
      parts.push(`<strong>${sub}</strong>`);
    }
    if (group) {
      parts.push("<strong>[平台名]</strong>");
    }
    parts.push("视频.mp4");

    previewPathText.innerHTML = parts.join(" / ");

    // 格式化主嗅探面板简要路径与主按钮文案
    const displayFolder = sub ? (group ? `Downloads/${sub}/<平台>` : `Downloads/${sub}`) : (group ? "Downloads/<平台>" : "Downloads");
    if (prompt) {
      currentDownloadPathDisplay.textContent = `${displayFolder} (弹窗另存为)`;
      btnPcDownloadPrimary.textContent = "💻 下载并选择保存位置... (另存为)";
    } else {
      currentDownloadPathDisplay.textContent = displayFolder;
      btnPcDownloadPrimary.textContent = `💻 一键下载到电脑 (${sub || "Downloads"})`;
    }
  }

  // 绑定下载目录实时输入预览与开关监听
  inputDownloadSubfolder.addEventListener("input", updateDownloadPreview);
  checkGroupByPlatform.addEventListener("change", updateDownloadPreview);
  checkPromptSaveas.addEventListener("change", updateDownloadPreview);

  // 初始触发一次路径预览刷新
  updateDownloadPreview();

  // 快速跳转至下载设置
  btnQuickConfigPath.addEventListener("click", () => {
    tabs.forEach(t => t.classList.remove("active"));
    tabPanes.forEach(p => p.classList.remove("active"));
    const settingsTab = document.querySelector('[data-tab="tab-settings"]');
    const settingsPane = document.getElementById("tab-settings");
    if (settingsTab && settingsPane) {
      settingsTab.classList.add("active");
      settingsPane.classList.add("active");
      inputDownloadSubfolder.focus();
    }
  });

  // 保存下载目录设置
  btnSaveDownloadSettings.addEventListener("click", async () => {
    const subfolder = (inputDownloadSubfolder.value || "").trim().replace(/^[\\/]+|[\\/]+$/g, "");
    const groupByPlatform = checkGroupByPlatform.checked;
    const promptSaveAs = checkPromptSaveas.checked;

    await chrome.storage.local.set({
      downloadSubfolder: subfolder,
      groupByPlatform,
      promptSaveAs
    });

    updateDownloadPreview();
    downloadSettingsStatus.className = "status-box success";
    downloadSettingsStatus.textContent = "✅ 下载目录设置已成功保存！";
    downloadSettingsStatus.classList.remove("hidden");
    setTimeout(() => {
      downloadSettingsStatus.classList.add("hidden");
    }, 3500);
  });

  // 复制浏览器默认下载设置 URL
  btnOpenEdgeSettings.addEventListener("click", () => {
    navigator.clipboard.writeText("edge://settings/downloads");
    btnOpenEdgeSettings.textContent = "✓ 已复制 Edge 地址";
    setTimeout(() => {
      btnOpenEdgeSettings.textContent = "复制 Edge 下载设置网址";
    }, 1800);
  });

  btnOpenChromeSettings.addEventListener("click", () => {
    navigator.clipboard.writeText("chrome://settings/downloads");
    btnOpenChromeSettings.textContent = "✓ 已复制 Chrome 地址";
    setTimeout(() => {
      btnOpenChromeSettings.textContent = "复制 Chrome 下载设置网址";
    }, 1800);
  });

  // 初始化检查
  testConnection(false);
  refreshCookiesState();
  renderTasks(cachedTasks);
  sniffCurrentTabMedia();

  function isProfileOrLikesUrl(url) {
    if (!url) return false;
    try {
      const u = new URL(url);
      const host = u.hostname.toLowerCase();
      const path = u.pathname.toLowerCase();
      if (host.includes("x.com") || host.includes("twitter.com")) {
        const parts = path.split("/").filter(Boolean);
        return parts.length >= 1 && !["home", "explore", "notifications", "messages", "search", "settings", "i"].includes(parts[0]);
      }
      if (host.includes("instagram.com")) {
        if (url.includes("interactions/likes") || url.includes("/saved/")) return true;
        const parts = path.split("/").filter(Boolean);
        return parts.length >= 1 && !["explore", "direct", "stories", "accounts"].includes(parts[0]);
      }
      if (host.includes("xiaohongshu.com")) {
        return path.includes("/user/profile/");
      }
    } catch (_) {}
    return false;
  }

  if (btnTriggerPageHarvest) {
    btnTriggerPageHarvest.addEventListener("click", async () => {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      if (tab && tab.id) {
        chrome.tabs.sendMessage(tab.id, { action: "OPEN_HARVESTER_MODE" });
        window.close();
      }
    });
  }

  // ── 核心：X / Instagram / YouTube / 小红书 深度媒体嗅探与下载 ───────────────
  async function sniffCurrentTabMedia() {
    try {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      if (!tab || !tab.url) return;

      mediaUrlText.textContent = tab.url;
      mediaTitleText.textContent = tab.title || "正在嗅探网页媒体...";

      // 检测主页/点赞页快捷横幅
      if (pageHarvesterBanner && isProfileOrLikesUrl(tab.url)) {
        pageHarvesterBanner.classList.remove("hidden");
        if (tab.url.includes("/likes") || tab.url.includes("interactions/likes")) {
          harvesterBannerTitle.textContent = "检测到点赞页，支持全量下载";
        } else {
          harvesterBannerTitle.textContent = "检测到博主主页，支持全量下载";
        }
      } else if (pageHarvesterBanner) {
        pageHarvesterBanner.classList.add("hidden");
      }

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

      // 绑定单项下载与复制（静默无感反馈，杜绝打扰弹窗）
      document.querySelectorAll(".btn-item-dl").forEach(btn => {
        btn.addEventListener("click", () => {
          const idx = parseInt(btn.dataset.idx, 10);
          const item = media.items[idx];
          btn.textContent = "✓ 已开始";
          btn.style.backgroundColor = "#059669";
          setTimeout(() => {
            btn.textContent = "下载";
            btn.style.backgroundColor = "";
          }, 1800);
          downloadSingleItem(item, media.platform);
        });
      });

      document.querySelectorAll(".btn-item-copy").forEach(btn => {
        btn.addEventListener("click", () => {
          navigator.clipboard.writeText(btn.dataset.url);
          btn.textContent = "✓ 已复制";
          setTimeout(() => {
            btn.textContent = "复制";
          }, 1800);
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
    if (res && res.success) {
      btnBatchDownloadImages.textContent = `✓ 全部 ${images.length} 张原图已开始下载`;
      btnBatchDownloadImages.style.backgroundColor = "#059669";
      setTimeout(() => {
        btnBatchDownloadImages.textContent = `📦 批量下载全部 ${images.length} 张原图`;
        btnBatchDownloadImages.style.backgroundColor = "";
      }, 2000);
    } else {
      btnBatchDownloadImages.textContent = `❌ 批量下载失败`;
      setTimeout(() => {
        btnBatchDownloadImages.textContent = `📦 批量下载全部 ${images.length} 张原图`;
      }, 2000);
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
        btnPcDownloadPrimary.textContent = "✓ 已开始下载";
        btnPcDownloadPrimary.style.backgroundColor = "#059669";
        setTimeout(() => {
          btnPcDownloadPrimary.style.backgroundColor = "";
          updateDownloadPreview();
        }, 1800);
      } else {
        btnPcDownloadPrimary.textContent = `❌ 下载受阻: ${res ? res.error : "未知错误"}`;
        setTimeout(() => {
          updateDownloadPreview();
        }, 2200);
      }
    } catch (e) {
      btnPcDownloadPrimary.textContent = `❌ 下载异常: ${e.message}`;
      setTimeout(() => {
        updateDownloadPreview();
      }, 2200);
    } finally {
      btnPcDownloadPrimary.disabled = false;
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
        btnPushToMobile.textContent = "✓ 已推送到手机！";
        btnPushToMobile.style.backgroundColor = "#059669";
        setTimeout(() => {
          btnPushToMobile.textContent = "🚀 推送到手机下载";
          btnPushToMobile.style.backgroundColor = "";
        }, 1800);
      } else {
        btnPushToMobile.textContent = "❌ 推送失败(请检查配对)";
        setTimeout(() => {
          btnPushToMobile.textContent = "🚀 推送到手机下载";
        }, 2000);
      }
    } catch (e) {
      btnPushToMobile.textContent = `❌ 推送异常: ${e.message}`;
      setTimeout(() => {
        btnPushToMobile.textContent = "🚀 推送到手机下载";
      }, 2000);
    } finally {
      btnPushToMobile.disabled = false;
    }
  });

  // 复制直链
  btnCopyMediaUrl.addEventListener("click", () => {
    if (currentResolvedMedia?.items?.[0]?.url) {
      navigator.clipboard.writeText(currentResolvedMedia.items[0].url);
      btnCopyMediaUrl.textContent = "✓ 已复制直链";
      setTimeout(() => {
        btnCopyMediaUrl.textContent = "📋 复制直链";
      }, 1800);
    } else {
      if (mediaUrlText.textContent) {
        navigator.clipboard.writeText(mediaUrlText.textContent);
        btnCopyMediaUrl.textContent = "✓ 已复制网页链接";
        setTimeout(() => {
          btnCopyMediaUrl.textContent = "📋 复制直链";
        }, 1800);
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
          <img src="${cover}" class="task-thumb" alt="封面" />
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

    // 严守 CSP 规范：杜绝内联 onerror，通过原生事件监听进行图片兜底
    tasksListContainer.querySelectorAll(".task-thumb").forEach(img => {
      img.addEventListener("error", () => {
        img.src = "../icons/icon48.png";
      }, { once: true });
    });
  }

  // ── 局域网配对数据清洗与协议解析核心 ───────────────────────────────────────
  function sanitizeUrl(raw) {
    if (!raw) return "";
    let u = String(raw).trim().replace(/：/g, ":").replace(/。/g, ".").replace(/\s+/g, "");
    if (u && !/^https?:\/\//i.test(u)) {
      u = "http://" + u;
    }
    return u.replace(/\/+$/, "");
  }

  function parsePairPayload(text) {
    if (!text) return null;
    text = String(text).trim();

    // 1. 标准 ClipDown 深度配对协议: clipdown://pair?url=http://...:8765&pin=...
    if (text.startsWith("clipdown://pair")) {
      try {
        const qIndex = text.indexOf("?");
        if (qIndex !== -1) {
          const params = new URLSearchParams(text.slice(qIndex + 1));
          const rawUrl = params.get("url");
          const rawPin = params.get("pin");
          if (rawUrl) {
            return {
              url: sanitizeUrl(rawUrl),
              pin: (rawPin || "").trim().replace(/\D/g, "").slice(0, 6)
            };
          }
        }
      } catch (_) {}
    }

    // 2. JSON 格式配对凭据
    if (text.startsWith("{") && text.endsWith("}")) {
      try {
        const obj = JSON.parse(text);
        if (obj.url || obj.ip) {
          const rawUrl = obj.url || `http://${obj.ip}:${obj.port || 8765}`;
          return {
            url: sanitizeUrl(rawUrl),
            pin: String(obj.pin || "").trim().replace(/\D/g, "").slice(0, 6)
          };
        }
      } catch (_) {}
    }

    // 3. 通用文本探测 (匹配 IP、端口与 6 位数字 PIN)
    const ipMatch = text.match(/((?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.(?:25[0-5]|2[0-4]\d|[01]?\d\d?)(?:\s*[:：]\s*\d{2,5})?)/);
    const pinMatch = text.match(/\b(\d{6})\b/);

    if (ipMatch) {
      let raw = ipMatch[1].replace(/\s+/g, "").replace(/：/g, ":");
      if (!raw.includes(":")) raw += ":8765";
      const cleanUrl = sanitizeUrl(raw);
      const pin = pinMatch ? pinMatch[1] : "";
      return { url: cleanUrl, pin };
    }

    return null;
  }

  async function applyAndConnectPair(pair, toastPrefix = "") {
    if (!pair || !pair.url) return;
    const cleanUrl = sanitizeUrl(pair.url);
    const cleanPin = (pair.pin || "").trim().replace(/\D/g, "").slice(0, 6);

    inputPhoneUrl.value = cleanUrl;
    if (cleanPin) inputPhonePin.value = cleanPin;

    await chrome.storage.local.set({ phoneUrl: cleanUrl, phonePin: cleanPin });

    // 自动切到「配对设置」页方便用户查看状态
    tabs.forEach(t => {
      if (t.dataset.tab === "tab-settings") t.classList.add("active");
      else t.classList.remove("active");
    });
    tabPanes.forEach(p => {
      if (p.id === "tab-settings") p.classList.add("active");
      else p.classList.remove("active");
    });

    if (cleanPin && cleanPin.length === 6) {
      showStatusBox(`${toastPrefix} 识别到地址: ${cleanUrl}，正在建立局域网连接...`, "info");
      await testConnection(true);
    } else {
      showStatusBox(`${toastPrefix} 已填入手机服务地址 (${cleanUrl})，请输入 6 位 PIN 码后点击连接`, "info");
      inputPhonePin.focus();
    }
  }

  // 输入框实时清洗（防误粘空格或全角冒号，自动解包配对链接）
  inputPhoneUrl.addEventListener("input", () => {
    const val = inputPhoneUrl.value;
    if (val.includes("clipdown://pair") || val.includes("pin=")) {
      const pair = parsePairPayload(val);
      if (pair) {
        inputPhoneUrl.value = pair.url;
        if (pair.pin) inputPhonePin.value = pair.pin;
        return;
      }
    }
    const sanitized = val.replace(/\s+/g, "").replace(/：/g, ":");
    if (sanitized !== val) {
      inputPhoneUrl.value = sanitized;
    }
  });

  inputPhonePin.addEventListener("input", () => {
    inputPhonePin.value = inputPhonePin.value.replace(/\D/g, "").slice(0, 6);
  });

  // ── 扫码配对核心逻辑 (BarcodeDetector + jsQR 双轨保障) ──────────────────────
  let cameraStream = null;
  let scanRafId = null;

  async function decodeQrFromVideoOrImage(source) {
    // 1. 优先采用 Chromium 原生硬件加速 BarcodeDetector (Chrome 88+)
    if (window.BarcodeDetector) {
      try {
        const detector = new window.BarcodeDetector({ formats: ["qr_code"] });
        const results = await detector.detect(source);
        if (results && results.length > 0 && results[0].rawValue) {
          return results[0].rawValue;
        }
      } catch (_) {}
    }

    // 2. 纯 JS 离线兜底 jsQR 引擎 (100% 稳定运行)
    if (typeof jsQR !== "undefined" && scannerCanvas) {
      try {
        const w = source.videoWidth || source.naturalWidth || source.width;
        const h = source.videoHeight || source.naturalHeight || source.height;
        if (w && h) {
          scannerCanvas.width = w;
          scannerCanvas.height = h;
          const ctx = scannerCanvas.getContext("2d", { willReadFrequently: true });
          ctx.drawImage(source, 0, 0, w, h);
          const imgData = ctx.getImageData(0, 0, w, h);
          const res = jsQR(imgData.data, imgData.width, imgData.height, {
            inversionAttempts: "dontInvert"
          }) || jsQR(imgData.data, imgData.width, imgData.height, {
            inversionAttempts: "onlyInvert"
          });
          if (res && res.data) {
            return res.data;
          }
        }
      } catch (e) {
        console.warn("jsQR decode error:", e);
      }
    }
    return null;
  }

  async function decodeQrFromBlob(blob) {
    const img = new Image();
    const blobUrl = URL.createObjectURL(blob);
    try {
      await new Promise((resolve, reject) => {
        img.onload = resolve;
        img.onerror = reject;
        img.src = blobUrl;
      });
      return await decodeQrFromVideoOrImage(img);
    } catch (_) {
      return null;
    } finally {
      URL.revokeObjectURL(blobUrl);
    }
  }

  async function startCameraScan() {
    scannerCameraStatus.classList.add("hidden");
    try {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error("当前环境未开放摄像头权限");
      }
      cameraStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "environment", width: { ideal: 640 }, height: { ideal: 480 } }
      });
      scannerVideo.srcObject = cameraStream;
      await scannerVideo.play();

      const scanLoop = async () => {
        if (!cameraStream) return;
        if (scannerVideo.readyState === scannerVideo.HAVE_ENOUGH_DATA) {
          const rawText = await decodeQrFromVideoOrImage(scannerVideo);
          if (rawText) {
            const pair = parsePairPayload(rawText);
            if (pair) {
              closeScannerModal();
              applyAndConnectPair(pair, "📷 扫码成功！");
              return;
            }
          }
        }
        scanRafId = requestAnimationFrame(scanLoop);
      };
      scanRafId = requestAnimationFrame(scanLoop);
    } catch (err) {
      console.warn("Camera access failed:", err);
      scannerCameraStatus.classList.remove("hidden");
      scannerStatusText.innerHTML = `未检测到可用摄像头或未授权<br><small style="color:#94A3B8;">请直接按 <strong>Ctrl + V</strong> 粘贴手机截屏，或点击下方选择图片</small>`;
    }
  }

  function stopCameraScan() {
    if (scanRafId) {
      cancelAnimationFrame(scanRafId);
      scanRafId = null;
    }
    if (cameraStream) {
      cameraStream.getTracks().forEach(t => t.stop());
      cameraStream = null;
    }
    scannerVideo.srcObject = null;
  }

  function openScannerModal() {
    modalScanner.classList.remove("hidden");
    startCameraScan();
  }

  function closeScannerModal() {
    stopCameraScan();
    modalScanner.classList.add("hidden");
    scannerCameraStatus.classList.add("hidden");
  }

  if (btnScanQr) {
    btnScanQr.addEventListener("click", openScannerModal);
  }
  if (btnCloseScanner) {
    btnCloseScanner.addEventListener("click", closeScannerModal);
  }
  if (modalScanner) {
    modalScanner.addEventListener("click", e => {
      if (e.target === modalScanner) closeScannerModal();
    });
  }
  if (btnUploadQrImg) {
    btnUploadQrImg.addEventListener("click", () => inputQrFile.click());
  }
  if (inputQrFile) {
    inputQrFile.addEventListener("change", async () => {
      const file = inputQrFile.files?.[0];
      if (!file) return;
      showStatusBox("正在识别图片中的二维码...", "info");
      const text = await decodeQrFromBlob(file);
      if (text) {
        const pair = parsePairPayload(text);
        if (pair) {
          closeScannerModal();
          applyAndConnectPair(pair, "📁 识别到图片中的配对信息！");
          return;
        }
      }
      showStatusBox("所选图片未包含有效的 ClipDown 配对二维码", "error");
      inputQrFile.value = "";
    });
  }

  // ── ⚡ 一键粘贴口令 ────────────────────────────────────────────────────────
  if (btnPastePairing) {
    btnPastePairing.addEventListener("click", async () => {
      try {
        const text = await navigator.clipboard.readText();
        if (!text || !text.trim()) {
          showStatusBox("剪贴板为空，请先在手机端配对弹窗点击「复制一键配对口令」", "error");
          return;
        }
        const pair = parsePairPayload(text);
        if (pair) {
          await applyAndConnectPair(pair, "⚡ 成功解析剪贴板配对口令！");
        } else {
          showStatusBox("剪贴板内容未包含有效配对信息，请确认已复制手机端口令", "error");
        }
      } catch (err) {
        showStatusBox(`读取剪贴板受阻 (${err.message})，可直接按 Ctrl + V 粘贴`, "error");
      }
    });
  }

  // ── 🔍 自动搜寻局域网手机 ──────────────────────────────────────────────────
  if (btnAutoDiscover) {
    btnAutoDiscover.addEventListener("click", async () => {
      btnAutoDiscover.disabled = true;
      btnAutoDiscover.textContent = "🔍 搜寻中...";
      showStatusBox("正在并发扫描局域网中的 ClipDown 手机端...", "info");

      try {
        const subnets = new Set();
        const currVal = inputPhoneUrl.value || "";
        const match = currVal.match(/(\d+\.\d+\.\d+)\.\d+/);
        if (match) subnets.add(match[1]);

        // 默认内置常见家用与手机热点 Wi-Fi 网段
        subnets.add("192.168.1");
        subnets.add("192.168.0");
        subnets.add("192.168.43"); // Android 热点
        subnets.add("172.20.10");  // iOS 热点
        subnets.add("192.168.31"); // 小米路由
        subnets.add("10.252.146"); // 校园网/办公网用户当前网段

        let priorityList = [];
        if (match) {
          const lastOctet = parseInt(currVal.match(/\.(\d+)(?::|$)/)?.[1] || "100", 10);
          for (let diff = 0; diff <= 20; diff++) {
            if (lastOctet + diff <= 254) priorityList.push(`${match[1]}.${lastOctet + diff}`);
            if (diff > 0 && lastOctet - diff >= 1) priorityList.push(`${match[1]}.${lastOctet - diff}`);
          }
        } else {
          for (let i = 1; i <= 30; i++) {
            priorityList.push(`192.168.1.${i}`);
            priorityList.push(`192.168.0.${i}`);
          }
        }

        async function probeHost(hostIp, port = 8765) {
          const testUrl = `http://${hostIp}:${port}/api/status`;
          const ctrl = new AbortController();
          const tid = setTimeout(() => ctrl.abort(), 900);
          try {
            const resp = await fetch(testUrl, { signal: ctrl.signal });
            clearTimeout(tid);
            if (resp.ok) {
              const data = await resp.json();
              if (data && (data.app === "ClipDown" || data.status === "OK" || data.deviceName)) {
                return { ip: hostIp, port, data };
              }
            }
          } catch (_) {
            clearTimeout(tid);
          }
          return null;
        }

        // 优先检查当前已输入的 IP
        if (currVal) {
          const parsed = currVal.replace(/^https?:\/\//, "").split(":")[0];
          const res = await probeHost(parsed);
          if (res) {
            showStatusBox(`🎉 已连通手机: ${res.data.deviceName || res.ip}`, "success");
            await testConnection(true);
            return;
          }
        }

        let found = null;
        const batchSize = 10;
        for (let i = 0; i < priorityList.length; i += batchSize) {
          const batch = priorityList.slice(i, i + batchSize);
          const results = await Promise.all(batch.map(ip => probeHost(ip)));
          found = results.find(r => r !== null);
          if (found) break;
        }

        if (found) {
          const phoneUrl = `http://${found.ip}:${found.port}`;
          inputPhoneUrl.value = phoneUrl;
          await chrome.storage.local.set({ phoneUrl });
          showStatusBox(`🎉 成功发现手机: ${found.data.deviceName || found.ip}！已自动填入地址`, "success");
          inputPhonePin.focus();
        } else {
          showStatusBox("未能在当前网段探测到手机。建议使用「📷 扫码连接」或对准手机屏幕二维码", "error");
        }
      } catch (e) {
        showStatusBox(`搜寻失败: ${e.message}`, "error");
      } finally {
        btnAutoDiscover.disabled = false;
        btnAutoDiscover.textContent = "🔍 自动搜寻";
      }
    });
  }

  // 全局 Ctrl + V 粘贴识别 (支持直接粘贴手机截图或口令)
  window.addEventListener("paste", async (e) => {
    const items = e.clipboardData?.items;
    if (!items) return;

    for (const item of items) {
      if (item.type.startsWith("image/")) {
        e.preventDefault();
        const file = item.getAsFile();
        if (file) {
          showStatusBox("正在解析粘贴的图片二维码...", "info");
          const text = await decodeQrFromBlob(file);
          if (text) {
            const pair = parsePairPayload(text);
            if (pair) {
              closeScannerModal();
              applyAndConnectPair(pair, "📋 识别到截图二维码！");
              return;
            }
          }
          showStatusBox("粘贴的图片中未能识别到 ClipDown 配对二维码", "error");
        }
        return;
      }
    }

    const pastedText = e.clipboardData.getData("text");
    if (pastedText && (pastedText.includes("clipdown://pair") || (pastedText.includes(":8765") && pastedText.includes("http")))) {
      const pair = parsePairPayload(pastedText);
      if (pair) {
        e.preventDefault();
        applyAndConnectPair(pair, "⚡ 识别到配对口令！");
      }
    }
  });

  // ── 配对设置保存与连接测试 ─────────────────────────────────────────────────
  btnTestSaveSettings.addEventListener("click", async () => {
    const url = sanitizeUrl(inputPhoneUrl.value);
    inputPhoneUrl.value = url;
    const pin = inputPhonePin.value.trim().replace(/\D/g, "");
    inputPhonePin.value = pin;

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
          const errDetail = resp ? (resp.error || "无法访问手机") : "服务无响应";
          showStatusBox(`❌ 连接失败: ${errDetail}`, "error");
        }
        footerStatus.textContent = "未连接手机";
      }
    } catch (err) {
      updateConnectionPill(false);
      if (showToast) {
        showStatusBox(`❌ 网络异常: ${err.message || err}`, "error");
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
