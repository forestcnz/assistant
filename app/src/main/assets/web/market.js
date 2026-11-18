(() => {
  const base = "/api/market";
  const clientIdKey = "assistant.market.oauthClientId";
  const refreshMs = 4000;
  const checkingMs = 1200;
  const activeStatuses = new Set(["submitting", "review", "queued", "building", "awaiting_catalog", "build_failed"]);
  const stepLabels = {
    submitting: "正在提交到 GitHub",
    review: "待合并",
    queued: "排队打包",
    building: "正在打包",
    awaiting_catalog: "等待市场更新",
    published: "已上架",
    superseded: "已上架，市场已有更新版本",
    failed: "提交失败",
    conflict: "市场仓库有更新",
    build_failed: "市场打包失败",
    closed: "PR 已关闭"
  };
  const routeLabels = {
    direct: "直接发布到市场仓库",
    branch: "在市场仓库创建 PR",
    fork: "通过你的 GitHub Fork 创建 PR"
  };

  const requestMarket = (api, path, body) => api(`${base}${path}`, {
    headers: { "X-Assistant-Market": "1" },
    ...(body === undefined ? {} : { method: "POST", body: JSON.stringify(body) })
  });

  const externalLink = (esc, url, label) => `<a href="${esc(url)}" target="_blank" rel="noopener noreferrer">${esc(label)}</a>`;

  function recordLinks(esc, record) {
    return [
      record.pullUrl && externalLink(esc, record.pullUrl, "查看 PR"),
      record.runUrl && externalLink(esc, record.runUrl, "查看 Actions"),
      record.sourceUrl && externalLink(esc, record.sourceUrl, "查看市场源码")
    ].filter(Boolean).join("");
  }

  window.openMarketPublisher = ({ task, api, esc, showModal, root, onHistory, onAuthorized, newRelease }) => {
    let panel;
    let session;
    let entries = [];
    let images = [];
    let formData;

    const request = (path, body) => requestMarket(api, path, body);
    const active = () => panel?.isConnected;
    const button = (id, label, primary = false) =>
      `<button type="button" id="${id}" class="${primary ? "primary" : "secondary"}-button">${esc(label)}</button>`;
    const close = '<button type="button" class="secondary-button" data-close-modal>关闭</button>';
    const link = (url, label) => externalLink(esc, url, label);
    const bytes = n => `${(n / 1024).toFixed(1)} KiB`;

    function show(title, body, actions = "") {
      showModal(`<div class="modal-head"><div><h3>${esc(title)}</h3>${task ? `<p>${esc(task.name)}</p>` : ""}</div>
        <button class="modal-close" data-close-modal aria-label="关闭">×</button></div>
        <div class="modal-body market-body">${body}<p class="market-error" role="alert" hidden></p></div>
        ${actions ? `<div class="modal-actions market-actions">${actions}</div>` : ""}`, true);
      panel = root.firstElementChild;
    }

    function showError(error) {
      if (!active()) return;
      const node = panel.querySelector(".market-error");
      node.textContent = error.message;
      node.hidden = false;
    }

    function action(id, run) {
      const node = panel.querySelector(`#${id}`);
      if (!node) return;
      node.onclick = async () => {
        node.disabled = true;
        panel.querySelector(".market-error").hidden = true;
        try { await run(); }
        catch (error) { if (node.isConnected) showError(error); }
        finally { if (node.isConnected) node.disabled = false; }
      };
    }

    async function enter() {
      const waiting = panel;
      session = await request("/session");
      if (!waiting.isConnected) return;
      if (!session.login) {
        if (session.device) device(session.device);
        else login();
        return;
      }
      if (onAuthorized) return onAuthorized();
      const submission = newRelease ? null : session.submissions.find(item => item.task === task.name);
      if (submission && (activeStatuses.has(submission.status) || submission.status === "failed")) {
        progress(submission);
      } else await loadForm();
    }

    function login(note = "") {
      // 浏览器仅记住公开 Client ID。GitHub 令牌随服务端会话保存。
      let clientId = "";
      try { clientId = localStorage.getItem(clientIdKey) || ""; } catch { /* 浏览器禁用站点存储 */ }
      show("授权 GitHub", `<p class="settings-hint">填写启用了 Device Flow 的 GitHub OAuth App 的 Client ID。
        ${link("https://github.com/settings/developers", "GitHub 应用设置")}</p>
        <form id="market-login-form"><label class="field"><span>OAuth Client ID</span>
        <input class="input" name="clientId" value="${esc(clientId)}" required maxlength="80" autocomplete="off" spellcheck="false"></label></form>
        ${note ? `<p class="settings-hint">${esc(note)}</p>` : ""}`, `${close}
        <button type="submit" form="market-login-form" class="primary-button">获取设备码</button>`);
      const form = panel.querySelector("form");
      const submit = panel.querySelector("[form='market-login-form']");
      form.onsubmit = async event => {
        event.preventDefault();
        const id = new FormData(form).get("clientId").trim();
        if (!id) return;
        submit.disabled = true;
        try {
          const pending = await request("/device", { clientId: id });
          if (!form.isConnected) return;
          try { localStorage.setItem(clientIdKey, id); } catch { /* 浏览器禁用站点存储 */ }
          device(pending);
        } catch (error) { if (form.isConnected) showError(error); }
        finally { if (form.isConnected) submit.disabled = false; }
      };
    }

    function device(pending) {
      show("在 GitHub 确认授权", `<p>在 GitHub 输入此设备码。</p>
        <p class="market-device-code" aria-label="GitHub 设备码">${esc(pending.userCode)}</p>
        ${link(pending.verificationUrl, "前往 GitHub 授权")}
        <p class="settings-hint" aria-live="polite">等待 GitHub 授权完成…</p>`, close);
      const waiting = panel;
      async function poll() {
        if (!waiting.isConnected) return;
        try {
          const next = await request("/device/poll", {});
          if (!waiting.isConnected) return;
          if (next.login) {
            session = next;
            if (onAuthorized) onAuthorized();
            else await loadForm();
          } else if (next.deviceState === "pending") {
            setTimeout(poll, next.pollAfterMs);
          } else login(next.deviceState === "denied" ? "GitHub 已取消授权。" : "设备码已过期，请重新获取。");
        } catch (error) {
          if (!waiting.isConnected) return;
          if (error.status === 401) login("设备码已过期，请重新获取。");
          else { showError(error); setTimeout(poll, 6500); }
        }
      }
      setTimeout(poll, pending.pollAfterMs);
    }

    async function loadForm() {
      show("发布到市场", '<p role="status">正在读取市场目录和任务素材…</p>', close);
      const loading = panel;
      const [catalog, assets] = await Promise.allSettled([
        request("/catalog"), api(`/api/tasks/${encodeURIComponent(task.name)}/images`)
      ]);
      if (!loading.isConnected) return;
      if (assets.status === "rejected") return showError(assets.reason);
      entries = catalog.status === "fulfilled" ? catalog.value.entries : [];
      images = assets.value.images.filter(item => /\.(png|jpe?g|webp)$/i.test(item.name));
      renderForm(catalog.status === "rejected" ? "市场目录暂不可读取，仍可发布新条目。" : "");
    }

    function renderForm(note = "") {
      const options = entries.map(item => [item.scriptId, `${item.targetApp || "通用"} / ${item.title} · v${item.version}`]);
      const field = (label, name, max, rows = 0, required = true) => `<label class="field"><span>${label}</span>
        ${rows ? `<textarea class="input" name="${name}" rows="${rows}" maxlength="${max}"${required ? " required" : ""}></textarea>` :
        `<input class="input" name="${name}" maxlength="${max}"${required ? " required" : ""}>`}</label>`;
      const controls = formData || {
        title: task.name, description: (task.description || "").slice(0, 200), targetPackage: task.target || "",
        minAppVersionCode: session.minAppVersionCode, changes: "首次上架。", images: images.map(item => item.name)
      };
      show("发布到市场", `<div class="market-account"><span>GitHub · ${esc(session.login)}</span>
        ${button("market-logout", "切换账号")}${button("market-history", "发布记录")}</div>
        <form id="market-form" class="settings-body">
          <label class="field"><span>市场条目</span><select class="input" name="updateId">
            <option value="">发布新条目</option>${options.map(([id, text]) => `<option value="${esc(id)}">${esc(text)}</option>`).join("")}
          </select></label>${note ? `<p class="settings-hint">${esc(note)}</p>` : ""}
          ${field("市场名称", "title", 50)}${field("简介", "description", 200, 2)}
          <div class="field-pair">${field("目标应用名称（通用任务可留空）", "targetApp", 80, 0, false)}
            ${field("目标应用包名", "targetPackage", 160, 0, false)}</div>
          ${field("使用说明", "usage", 2000, 3)}${field("实测环境", "environment", 500, 2)}
          ${field("所需权限或能力（每行一项）", "capabilities", 650, 2, false)}
          ${field("本次更新说明", "changes", 500, 2)}
          <label class="field"><span>最低助手版本号（Version Code）</span>
            <input class="input" name="minAppVersionCode" type="number" min="1" max="2147483647" required></label>
          <fieldset class="market-assets"><legend>公开素材</legend>
            ${images.length ? `<button type="button" id="market-select-images" class="secondary-button">全选 / 取消全选</button>
              <div class="market-image-grid">${images.map(item => `<label title="${esc(item.name)}">
                <img loading="lazy" alt="" src="/api/tasks/${encodeURIComponent(task.name)}/images/${encodeURIComponent(item.name)}">
                <span><input type="checkbox" name="image" value="${esc(item.name)}"> ${esc(item.name)}</span></label>`).join("")}</div>` :
              '<p class="settings-hint">没有可发布的图片。</p>'}
          </fieldset>
        </form>`, `${close}<button type="submit" form="market-form" class="primary-button">预览发布内容</button>`);
      const form = panel.querySelector("form");
      const submit = panel.querySelector("[form='market-form']");
      const input = name => form.elements.namedItem(name);
      function fill(fields) {
        for (const name of ["updateId", "title", "description", "targetApp", "targetPackage", "usage", "environment", "capabilities", "changes", "minAppVersionCode"]) {
          input(name).value = fields[name] ?? "";
        }
        input("title").readOnly = input("targetApp").readOnly = Boolean(fields.updateId);
      }
      fill(controls);
      const checks = [...form.querySelectorAll("input[name='image']")];
      checks.forEach(node => node.checked = controls.images.includes(node.value));
      action("market-select-images", () => {
        const checked = !checks.every(node => node.checked);
        checks.forEach(node => node.checked = checked);
      });
      input("updateId").onchange = () => {
        const old = entries.find(item => item.scriptId === input("updateId").value);
        if (old) fill({ ...old, updateId: old.scriptId, capabilities: old.capabilities.join("\n") });
        else fill({ title: task.name, description: (task.description || "").slice(0, 200),
          targetPackage: task.target || "", changes: "首次上架。", minAppVersionCode: session.minAppVersionCode });
      };
      action("market-logout", logout);
      action("market-history", onHistory);
      form.onsubmit = async event => {
        event.preventDefault();
        const data = new FormData(form);
        formData = Object.fromEntries(data);
        delete formData.image;
        formData.images = data.getAll("image");
        const draft = { ...formData, task: task.name, minAppVersionCode: Number(formData.minAppVersionCode),
          capabilities: formData.capabilities.split(/\r?\n/).map(text => text.trim()).filter(Boolean) };
        submit.disabled = true;
        panel.querySelector(".market-error").hidden = true;
        try {
          const preview = await request("/preview", draft);
          if (form.isConnected) previewScreen(preview);
        } catch (error) { if (form.isConnected) showError(error); }
        finally { if (form.isConnected) submit.disabled = false; }
      };
    }

    function previewScreen(preview) {
      const release = preview.release;
      const id = encodeURIComponent(preview.id);
      show("确认公开内容", `<div class="market-summary"><strong>${esc(release.title)} · v${release.version}</strong>
        <p>${esc(routeLabels[preview.route])}</p><p>${esc(preview.directory)} · ZIP ${bytes(preview.zipBytes)}</p>
        <p>${esc(release.description)}</p></div>
        <ul class="market-file-list">${preview.files.map(item => `<li>${esc(item.path)} <small>${bytes(item.bytes)}</small></li>`).join("")}</ul>
        ${button("market-source", "查看源码和发布信息")}<div id="market-source-content"></div>
        <div class="market-image-grid">${preview.files.filter(item => item.path.startsWith("images/")).map(item => {
          const name = item.path.slice(7);
          return `<figure><a href="${base}/preview/${id}/image/${encodeURIComponent(name)}" target="_blank" rel="noopener noreferrer">
            <img loading="lazy" src="${base}/preview/${id}/image/${encodeURIComponent(name)}" alt="${esc(name)}"></a><figcaption>${esc(name)}</figcaption></figure>`;
        }).join("")}</div>`, `${button("market-back", "返回修改")}
        ${button("market-submit", preview.route === "direct" ? "确认发布" : "提交审核", true)}`);
      action("market-back", () => renderForm());
      action("market-source", async () => {
        const currentPanel = panel;
        const files = await request(`/preview/${id}`);
        if (!currentPanel.isConnected) return;
        currentPanel.querySelector("#market-source-content").innerHTML = Object.entries(files).map(([name, text]) =>
          `<details class="market-code"><summary>${esc(name)}</summary><pre class="code-preview">${esc(text)}</pre></details>`).join("");
        currentPanel.querySelector("#market-source").hidden = true;
      });
      action("market-submit", async () => {
        const currentPanel = panel;
        const result = await request("/submit", { id: preview.id });
        if (currentPanel.isConnected) progress(result);
      });
    }

    async function logout() {
      const previous = panel;
      await request("/logout", {});
      if (previous.isConnected) login();
    }

    function progress(item) {
      const id = encodeURIComponent(item.id);
      show("发布进度", `<div class="market-summary" aria-live="polite">
        <strong>${esc(stepLabels[item.status] || item.status)}</strong><p>${esc(item.title)} · v${item.version}</p>
        ${item.error || item.checkError ? `<p>${esc(item.error || item.checkError)}</p>` : ""}
        <div class="market-links">${recordLinks(esc, item)}</div></div>`,
      `${close}${button("market-history", "发布记录")}
        ${item.status === "failed" ? button("market-retry", "重试本次发布", true) : ""}
        ${item.status === "conflict" ? button("market-edit", "重新预览", true) : ""}`);
      const shown = panel;
      action("market-history", onHistory);
      action("market-edit", loadForm);
      action("market-retry", async () => {
        const next = await request(`/submissions/${id}/retry`, {});
        if (shown.isConnected) progress(next);
      });
      if (!activeStatuses.has(item.status)) return;
      async function poll() {
        if (!shown.isConnected) return;
        try {
          const { checking, ...next } = await request(`/submissions/${id}`);
          if (!shown.isConnected) return;
          if (JSON.stringify(next) !== JSON.stringify(item)) progress(next);
          else setTimeout(poll, checking ? checkingMs : refreshMs);
        } catch (error) {
          if (!shown.isConnected) return;
          showError(error);
          if (error.status !== 401 && error.status !== 404) setTimeout(poll, 6500);
        }
      }
      setTimeout(poll, 750);
    }

    show("发布到市场", '<p role="status">正在读取发布会话…</p>', close);
    const opening = panel;
    enter().catch(error => {
      if (!opening.isConnected) return;
      if (error.status === 403) {
        show("连接市场发布", `<p>${esc(error.message)}</p>
          <p class="settings-hint">在手机浏览器打开 ${link("http://127.0.0.1:8765/", "本机 Web 编辑器")}。
          电脑可使用 USB 调试端口转发或可信 HTTPS 反向代理。</p>`, close);
      } else showError(error);
    });
  };

  window.createMarketHistory = ({ root, api, esc, onLogin, onNew }) => {
    const list = root.querySelector("#market-history-list");
    const sessionLine = root.querySelector("#market-history-session");
    const refreshButton = root.querySelector("#market-history-refresh");
    const newButton = root.querySelector("#market-history-new");
    const errorLine = root.querySelector("#market-history-error");
    let visible = false;
    let revision = 0;
    let timer;
    let login = "";
    let records = [];
    let lastRender;
    let refreshing = false;

    function setError(error) {
      errorLine.textContent = error?.message ?? "";
      errorLine.hidden = !error;
    }

    function showRecords(next) {
      records = next;
      const serialized = JSON.stringify(records);
      if (serialized === lastRender) return;
      lastRender = serialized;
      list.innerHTML = records.length ? records.map(record => {
        const detail = record.error || record.checkError;
        return `<article class="market-record">
          <header class="market-record-head"><h2>${esc(record.title)} <small>v${esc(record.version)}</small></h2>
          <span class="market-record-status" data-status="${esc(record.status)}">${esc(stepLabels[record.status] || record.status)}</span></header>
          <p class="market-record-route">${esc(routeLabels[record.route] || "")}</p>
          ${detail ? `<p class="market-record-detail">${esc(detail)}</p>` : ""}
          <div class="market-links">${recordLinks(esc, record)}${record.status === "failed" ?
            `<button class="secondary-button" data-market-retry="${esc(record.id)}">重试本次发布</button>` : ""}</div>
        </article>`;
      }).join("") : '<div class="market-history-empty">暂无发布记录</div>';
    }

    function showLogin() {
      login = "";
      refreshing = false;
      refreshButton.disabled = true;
      sessionLine.textContent = "";
      lastRender = undefined;
      list.innerHTML = '<div class="market-history-empty"><p>授权 GitHub 后即可查看当前会话的发布记录。</p><button type="button" class="primary-button" data-market-login>授权 GitHub</button></div>';
    }

    function schedule(delay = refreshMs) {
      clearTimeout(timer);
      if (visible && !document.hidden && login && records.some(record => activeStatuses.has(record.status))) {
        timer = setTimeout(() => refresh(), delay);
      }
    }

    async function refresh(force = false) {
      if (!visible || !login || refreshing) return;
      const turn = revision;
      clearTimeout(timer);
      refreshing = true;
      refreshButton.disabled = true;
      let nextDelay = refreshMs;
      try {
        const data = await requestMarket(api, `/submissions${force ? "?refresh=1" : ""}`);
        if (turn !== revision) return;
        showRecords(data.submissions);
        nextDelay = data.checking ? checkingMs : refreshMs;
        const status = data.checking ? "正在查询状态…" : `最近刷新 ${new Date().toLocaleTimeString("zh-CN", { hour12: false })}`;
        sessionLine.textContent = `GitHub · ${login} · ${status}`;
        setError(null);
      } catch (error) {
        if (turn !== revision) return;
        if (error.status === 401) showLogin();
        else setError(error);
      } finally {
        if (turn === revision) {
          refreshing = false;
          refreshButton.disabled = !login;
          schedule(nextDelay);
        }
      }
    }

    async function open() {
      if (!visible) return;
      const turn = ++revision;
      clearTimeout(timer);
      refreshing = false;
      login = "";
      records = [];
      refreshButton.disabled = true;
      sessionLine.textContent = "正在读取发布记录…";
      lastRender = undefined;
      list.innerHTML = "";
      setError(null);
      try {
        const data = await requestMarket(api, "/session");
        if (turn !== revision) return;
        if (!data.login) return showLogin();
        login = data.login;
        sessionLine.textContent = `GitHub · ${login} · 正在查询状态…`;
        showRecords(data.submissions);
        await refresh(true);
      } catch (error) {
        if (turn !== revision) return;
        sessionLine.textContent = "";
        setError(error);
        if (error.status === 403) {
          list.innerHTML = `<div class="market-history-empty">${externalLink(esc, "http://127.0.0.1:8765/#market-history", "打开本机发布记录")}</div>`;
        } else refreshButton.disabled = false;
      }
    }

    refreshButton.onclick = () => login ? refresh(true) : open();
    newButton.onclick = onNew;
    list.addEventListener("click", async event => {
      if (event.target.closest("[data-market-login]")) return onLogin();
      const button = event.target.closest("[data-market-retry]");
      if (!button) return;
      const turn = revision;
      button.disabled = true;
      try {
        const record = await requestMarket(api, `/submissions/${encodeURIComponent(button.dataset.marketRetry)}/retry`, {});
        if (turn !== revision) return;
        showRecords(records.map(item => item.id === record.id ? record : item));
        setError(null);
        schedule();
      } catch (error) {
        if (turn === revision) setError(error);
      } finally {
        if (button.isConnected) button.disabled = false;
      }
    });
    // 页面进入前台时立即同步，退出页面后停止定时刷新。
    document.addEventListener("visibilitychange", () => {
      if (document.hidden) clearTimeout(timer);
      else if (visible && login) refresh(true);
    });
    return {
      open,
      setVisible(value) {
        if (visible === value) return;
        visible = value;
        if (visible) open();
        else {
          ++revision;
          clearTimeout(timer);
        }
      }
    };
  };
})();
