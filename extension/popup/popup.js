/**
 * ClipDown 跨端助手 - Popup 交互逻辑
 */

document.addEventListener("DOMContentLoaded", async () => {
  // ── DOM 元素获取 ───────────────────────────────────────────────────────────
  const tabs = document.querySelectorAll(".tab-btn");
  const tabPanes = document.querySelectorAll(".tab-pane");
  const connectionPill = document.getElementById("connection-pill");
  const footerStatus = document.getElementById("footer-status");

  // 设置页元素
  const inputPhoneUrl = document.getElementById("input-phone-url");
  const inputPhonePin = document.getElementById("input-phone-pin");
  const btnTestSaveSettings = document.getElementById("btn-test-save-settings");
  const settingsStatusBox = document.getElementById("settings-status-box");

  // Cookie 共享页元素
  const btnSyncAllCookies = document.getElementById("btn-sync-all-cookies");
  const btnQrSync = document.getElementById("btn-qr-sync");
  const btnCopyDeeplink = document.getElementById("btn-copy-deeplink");

  // 任务记录页元素
  const btnPullTasks = document.getElementById("btn-pull-tasks");
  const tasksStatsText = document.getElementById("tasks-stats-text");
  const tasksSearchInput = document.getElementById("tasks-search-input");
  const tasksListContainer = document.getElementById("tasks-list-container");

  // 嗅探页元素
  const currentTabTitle = document.getElementById("current-tab-title");
  const currentTabUrl = document.getElementById("current-tab-url");
  const btnEnqueueCurrent = document.getElementById("btn-enqueue-current");
  const manualUrlInput = document.getElementById("manual-url-input");
  const btnManualEnqueue = document.getElementById("btn-manual-enqueue");

  // 扫码弹窗
  const modalQr = document.getElementById("modal-qr");
  const btnCloseQr = document.getElementById("btn-close-qr");
  const qrPlatformSelect = document.getElementById("qr-platform-select");
  const qrcodeContainer = document.getElementById("qrcode-container");

  let currentTabInfo = null;
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

  // 初始化检查连接与 Cookie 状态
  await testConnection(false);
  await refreshCookiesState();
  await inspectCurrentTab();
  renderTasks(cachedTasks);

  // ── 连接测试与保存 ─────────────────────────────────────────────────────────
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

  // ── 检查电脑浏览器 Cookie 状态 ─────────────────────────────────────────────
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

  // 单平台同步
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

  // 一键同步所有 Cookie 到手机
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

  // 离线备用：扫码同步
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

  // 离线备用：复制 DeepLink 协议
  btnCopyDeeplink.addEventListener("click", async () => {
    const defaultPlat = "instagram";
    const info = allPlatformCookies[defaultPlat] || Object.values(allPlatformCookies).find(x => x.loggedIn);
    if (!info || !info.cookie) {
      alert("当前电脑浏览器未检测到已登录的平台");
      return;
    }
    const deepLink = `clipdown://cookie?platform=${info.platform}&data=${encodeURIComponent(info.cookie)}`;
    await navigator.clipboard.writeText(deepLink);
    alert(`已复制 ${info.name} 的一键同步协议链接：\n\n${deepLink.substring(0, 80)}...\n\n可直接发到手机微信/QQ点击打开，或在手机浏览器中访问即可秒导！`);
  });

  // ── 任务记录双向同步 ───────────────────────────────────────────────────────
  btnPullTasks.addEventListener("click", async () => {
    btnPullTasks.disabled = true;
    btnPullTasks.textContent = "同步中...";
    try {
      const resp = await chrome.runtime.sendMessage({ action: "PULL_TASKS" });
      if (resp && resp.success) {
        const phoneTasks = (resp.data && resp.data.tasks) || [];
        // 合并本地与手机任务
        const map = new Map();
        for (const t of cachedTasks) map.set(t.id, t);
        for (const t of phoneTasks) map.set(t.id, t);
        cachedTasks = Array.from(map.values()).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));

        await chrome.storage.local.set({ cached_tasks: cachedTasks });
        renderTasks(cachedTasks);
        alert(`✅ 同步完成！共拉取合并 ${cachedTasks.length} 条下载记录（仅同步记录，无大文件传输）`);
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

  // ── 快捷嗅探 ───────────────────────────────────────────────────────────────
  async function inspectCurrentTab() {
    try {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      if (tab) {
        currentTabInfo = tab;
        currentTabTitle.textContent = tab.title || "未知网页";
        currentTabUrl.textContent = tab.url || "";
      }
    } catch (e) {
      console.warn("Inspect tab failed:", e);
    }
  }

  btnEnqueueCurrent.addEventListener("click", async () => {
    if (!currentTabInfo || !currentTabInfo.url) {
      alert("未能获取当前标签页链接");
      return;
    }
    await enqueueUrl(currentTabInfo.url);
  });

  btnManualEnqueue.addEventListener("click", async () => {
    const url = manualUrlInput.value.trim();
    if (!url) {
      alert("请输入要下载的链接");
      return;
    }
    await enqueueUrl(url);
    manualUrlInput.value = "";
  });

  async function enqueueUrl(url) {
    try {
      const resp = await chrome.runtime.sendMessage({
        action: "ENQUEUE_URL",
        url
      });
      if (resp && resp.success) {
        alert("🚀 成功推送到手机！手机悬浮球与下载中枢已收到任务。");
      } else {
        alert(`❌ 推送失败: ${resp ? resp.error : "请先连接手机"}`);
      }
    } catch (err) {
      alert(`❌ 推送异常: ${err.message}`);
    }
  }
});
