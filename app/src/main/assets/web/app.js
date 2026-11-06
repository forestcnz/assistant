(() => {
  const dom = {
    taskList: document.getElementById("task-list"),
    search: document.getElementById("task-search"),
    taskRail: document.getElementById("task-rail"),
    empty: document.getElementById("empty-editor"),
    editor: document.getElementById("editor"),
    content: document.getElementById("editor-content"),
    taskName: document.getElementById("current-task-name"),
    saveState: document.getElementById("save-state"),
    meta: document.getElementById("task-meta"),
    modal: document.getElementById("modal-layer"),
    toast: document.getElementById("toast"),
    imageInput: document.getElementById("image-file-input"),
    assetInput: document.getElementById("asset-file-input"),
    saveButton: document.getElementById("save-task"),
    previewButton: document.getElementById("preview-code"),
    serverState: document.getElementById("server-state")
  };

  const catalog = {
    common: [
      ["click", "点击", "坐标操作"],
      ["swipe", "滑动", "起点与终点"],
      ["wait", "等待", "设置停留时间"],
      ["log", "记录日志", "输出运行信息"]
    ],
    control: [
      ["sequence", "顺序组", "整组按序执行"],
      ["repeat:count", "固定循环", "重复指定次数"],
      ["repeat:while", "条件循环", "条件成立时执行"],
      ["repeat:until", "直到满足", "满足条件后退出"],
      ["repeat:forever", "持续循环", "直到跳出或停止"],
      ["condition", "条件分支", "如果 / 否则"],
      ["break", "退出循环", "跳出最近一层"],
      ["continue", "进入下一轮", "跳过本轮余下步骤"],
      ["endRound", "结束本轮", "完成当前整任务轮次"],
      ["stop", "停止运行", "终止后续任务"]
    ],
    screen: [
      ["image", "找图", "匹配图片，返回位置"],
      ["text", "找字", "匹配文字，返回位置"]
    ]
  };
  const catalogFlat = Object.values(catalog).flat();
  const categoryNames = { common: "常用动作", control: "流程控制", screen: "屏幕识别" };

  let tasks = [];
  let current = null;
  let mode = "script";
  const dirtyModes = new Set();
  let busy = false;
  let assets = null;
  let assetsError = "";
  let assetView = "current";
  let imageHistory = null;
  let historyError = "";
  let assetRevision = 0;
  let flow = null;
  let selectedId = null;
  let category = "common";
  let insertTarget = { parentId: null, slot: "children", index: Number.MAX_SAFE_INTEGER };
  const expanded = new Set();
  const canvasView = { x: 0, y: 32, zoom: 1 };
  let canvasPanKey = false;
  let drag = null;
  let suppressAddClick = false;
  let pendingImageNode = null;
  let toastTimer = null;

  function esc(value) {
    return String(value ?? "").replace(/[&<>"']/g, c => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    })[c]);
  }

  function uid() {
    return `node-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
  }

  function toast(message, error = false) {
    clearTimeout(toastTimer);
    dom.toast.textContent = message;
    dom.toast.className = `toast show${error ? " error" : ""}`;
    toastTimer = setTimeout(() => dom.toast.className = "toast", 2600);
  }

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (options.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json; charset=UTF-8");
    const response = await fetch(path, { ...options, headers });
    const text = await response.text();
    let data = null;
    try { data = text ? JSON.parse(text) : {}; } catch { data = { source: text }; }
    if (!response.ok) throw new Error(data.error || `请求失败 (${response.status})`);
    return data;
  }

  function setDirty(value) {
    if (value) dirtyModes.add(mode);
    else dirtyModes.delete(mode);
    updateSaveState();
  }

  function updateSaveState() {
    const dirty = dirtyModes.size > 0;
    dom.saveState.textContent = dirty ? "有未保存修改" : "已保存";
    dom.saveState.style.color = dirty ? "var(--orange-dark)" : "var(--muted)";
    dom.saveButton.disabled = !dirtyModes.has(mode);
  }

  function confirmDiscard() {
    return !dirtyModes.size || confirm("当前修改尚未保存，确定离开吗？");
  }

  function taskPath(name = current.name) {
    return `/api/tasks/${encodeURIComponent(name)}`;
  }

  async function perform(action) {
    if (busy) return;
    busy = true;
    dom.editor.inert = dom.taskRail.inert = dom.empty.inert = dom.modal.inert = true;
    try {
      await action();
    } catch (error) {
      toast(error.message, true);
    } finally {
      busy = false;
      dom.editor.inert = dom.taskRail.inert = dom.empty.inert = dom.modal.inert = false;
    }
  }

  async function loadTasks() {
    try {
      const data = await api("/api/tasks");
      tasks = data.tasks || [];
      renderTaskList();
      dom.serverState.classList.add("online");
      dom.serverState.innerHTML = '<span class="state-dot"></span><span>局域网已连接</span>';
    } catch (error) {
      dom.serverState.innerHTML = '<span class="state-dot"></span><span>连接失败</span>';
      toast(error.message, true);
    }
  }

  const descriptionObserver = new ResizeObserver(entries => {
    for (const { target } of entries) {
      const distance = Math.max(0, target.firstElementChild.scrollWidth - target.clientWidth);
      target.style.setProperty("--desc-distance", `-${distance}px`);
      target.style.setProperty("--desc-duration", `${Math.max(6, distance / 20 + 4)}s`);
      target.classList.toggle("overflowing", distance > 1);
    }
  });

  function renderTaskList() {
    descriptionObserver.disconnect();
    const query = dom.search.value.trim().toLowerCase();
    const filtered = tasks.filter(task =>
      !query || task.name.toLowerCase().includes(query) || (task.description || "").toLowerCase().includes(query)
    );
    if (!filtered.length) {
      dom.taskList.innerHTML = '<div class="inspector-empty">没有匹配的任务</div>';
      return;
    }
    dom.taskList.innerHTML = filtered.map(task => `
      <button class="task-item${current?.name === task.name ? " active" : ""}" data-task="${esc(task.name)}">
        <span class="task-line">
          <span class="task-name">${esc(task.name)}</span>
          <span class="task-badge ${task.type}">${task.type === "flow" ? "流程" : "脚本"}</span>
        </span>
        <span class="task-desc" title="${esc(task.description)}"><span>${esc(task.description || (task.imageCount ? `${task.imageCount} 个素材` : "任务目录"))}</span></span>
      </button>
    `).join("");
    dom.taskList.querySelectorAll(".task-desc").forEach(desc => descriptionObserver.observe(desc));
  }

  async function openTask(name) {
    if (busy) return;
    if (current?.name === name) return dom.taskRail.classList.remove("open");
    if (!confirmDiscard()) return;
    await perform(async () => {
      const data = await api(taskPath(name));
      const nextFlow = data.type === "flow" ? JSON.parse(data.source) : null;
      current = data;
      mode = data.type === "flow" ? "flow" : "script";
      flow = nextFlow;
      assets = null;
      assetsError = "";
      assetView = "current";
      imageHistory = null;
      historyError = "";
      assetRevision = 0;
      Object.assign(canvasView, { x: 0, y: 32, zoom: 1 });
      selectedId = null;
      expanded.clear();
      (flow?.nodes || []).forEach(node => expanded.add(node.id));
      insertTarget = { parentId: null, slot: "children", index: Number.MAX_SAFE_INTEGER };
      dirtyModes.clear();
      dom.empty.hidden = true;
      dom.editor.hidden = false;
      dom.taskRail.classList.remove("open");
      renderEditor();
      renderTaskList();
    });
  }

  function renderEditor() {
    dom.taskName.textContent = current.name;
    const loop = current.loopMode === "duration" ? `${current.durationSec} 秒`
      : current.loopMode === "untilStopped" ? "直到手动停止" : `${current.globalCount} 次`;
    dom.meta.textContent = `${current.type === "flow" ? "可视化流程" : "JavaScript"} · ${loop}`;
    dom.saveButton.hidden = dom.previewButton.hidden = mode === "assets";
    updateSaveState();
    document.querySelectorAll(".tab").forEach(tab => {
      tab.hidden = tab.dataset.mode === "flow" && current?.type !== "flow";
      tab.classList.toggle("active", tab.dataset.mode === mode);
    });
    if (mode === "script") renderScriptEditor();
    else if (mode === "flow") renderFlowEditor();
    else renderAssets();
  }

  function renderScriptEditor() {
    const editedFromFlow = current.type === "flow";
    if (current.editorSource == null) current.editorSource = current.scriptSource ?? current.source;
    dom.content.innerHTML = `
      <div class="script-workspace">
        <div class="code-frame">
          <div class="code-head"><span class="code-dot"></span><strong>task.js</strong><span>JAVASCRIPT</span></div>
          <textarea class="script-editor" id="script-editor" spellcheck="false">${esc(current.editorSource)}</textarea>
        </div>
        <div class="script-hint">${editedFromFlow
          ? "当前显示流程生成的 task.js。修改并保存后，任务会转换为普通脚本任务。"
          : "支持 Ctrl / Command + S 保存。脚本将直接写入当前任务的 task.js。"}</div>
      </div>`;
    const editor = document.getElementById("script-editor");
    editor.addEventListener("input", () => {
      current.editorSource = editor.value;
      setDirty(true);
    });
    editor.addEventListener("keydown", event => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        saveCurrent();
      }
      if (event.key === "Tab") {
        event.preventDefault();
        const start = editor.selectionStart;
        editor.setRangeText("  ", start, editor.selectionEnd, "end");
        current.editorSource = editor.value;
        setDirty(true);
      }
    });
  }

  function newNode(def) {
    const [typeValue, forcedMode] = def[0].split(":");
    const type = typeValue;
    const node = {
      id: uid(),
      type,
      title: def[1],
      enabled: true,
      props: { failure: "stop" }
    };
    if (type === "click") Object.assign(node.props, { x: 960, y: 540, duration: 50 });
    if (type === "swipe") Object.assign(node.props, { x: 960, y: 800, endX: 960, endY: 300, duration: 300 });
    if (type === "wait") node.props.duration = 1000;
    if (type === "log") Object.assign(node.props, { text: "流程运行完成", level: "info" });
    if (type === "repeat") {
      Object.assign(node.props, { mode: forcedMode || "count", count: 5, interval: 300 });
      node.children = [];
    }
    if (type === "sequence") node.children = [];
    if (type === "condition") {
      Object.assign(node.props, { conditionKind: "image", conditionValue: "", combine: "single" });
      node.then = [];
      node.else = [];
    }
    if (type === "image") {
      Object.assign(node.props, {
        asset: "", threshold: 0.9, timeout: 5000, poll: 300,
        onMatch: "recognize", onMiss: "continue", region: ""
      });
    }
    if (type === "text") {
      Object.assign(node.props, {
        text: "", match: "contains", timeout: 5000, poll: 300,
        onMatch: "recognize", onMiss: "continue", region: ""
      });
    }
    return node;
  }

  function nodeProperties(node) {
    return { ...node.props, ...node };
  }

  function nodeTitle(node) {
    if (node.type !== "repeat") return node.title;
    const p = nodeProperties(node);
    const modeValue = p.mode || "count";
    if (modeValue === "while") return "当条件成立时";
    if (modeValue === "until") return "重复直到满足";
    if (modeValue === "forever") return "持续循环";
    return `重复 ${p.count || 1} 次`;
  }

  function nodeSummary(node) {
    const p = nodeProperties(node);
    switch (node.type) {
      case "sequence": return `${countLeaves(node)} 个动作 · 按序执行`;
      case "repeat": return `轮间隔 ${p.interval || 0} ms · ${(node.children || []).length} 个步骤`;
      case "click": return `X ${p.x || 0} / Y ${p.y || 0}`;
      case "swipe": return `滑动 ${p.duration || 300} ms`;
      case "wait": return `等待 ${p.duration || 1000} ms`;
      case "condition": return `${p.conditionKind === "text" ? "文字存在" : "图片存在"} · ${p.conditionValue || "未设置"}`;
      case "image": return `${p.asset || "未选择素材"} · ${p.onMatch === "click" ? "命中后点击" : "仅识别"}`;
      case "text": return `查找「${p.text || "未填写"}」 · ${p.onMatch === "click" ? "命中后点击" : "仅识别"}`;
      case "log": return p.text || "记录日志";
      case "stop": return "停止当前任务与剩余队列";
      case "continue": return "跳过本轮余下步骤";
      case "break": return "退出最近一层循环";
      case "endRound": return "完成当前整任务轮次";
      default: return node.type;
    }
  }

  function assetUrl(asset) {
    const name = asset.replace(/^images[\\/]/, "");
    return `${taskPath()}/images/${encodeURIComponent(name)}`;
  }

  function assetPreviewUrl(asset, version = assets?.find(item => item.name === asset)?.version) {
    return `${assetUrl(asset)}?v=${encodeURIComponent(version || "")}.${assetRevision}`;
  }

  function historyAssetUrl(id) {
    return `${taskPath()}/image-history/${encodeURIComponent(id)}`;
  }

  function previewAsset(asset, historyId = null) {
    const url = historyId ? historyAssetUrl(historyId) : assetPreviewUrl(asset);
    showModal(`
      <div class="modal-head">
        <div><h3>${historyId ? "历史素材预览" : "图片预览"}</h3><p>${esc(asset)}</p></div>
        <button class="modal-close" data-close-modal>×</button>
      </div>
      <div class="modal-body image-preview-body">
        <img class="image-preview-img" src="${esc(url)}" alt="${esc(asset)}">
      </div>
      <div class="modal-actions image-preview-actions">
        ${historyId ? `<button class="primary-button" data-restore-asset="${esc(historyId)}">移回素材</button>`
          : `<button class="primary-button" data-crop-asset="${esc(asset)}">裁剪图片</button>`}
      </div>`, true);
  }

  function countLeaves(node) {
    if (node.enabled === false) return 0;
    if (node.children?.length) return node.children.reduce((sum, child) => sum + countLeaves(child), 0);
    if (node.then?.length || node.else?.length) {
      return [...(node.then || []), ...(node.else || [])].reduce((sum, child) => sum + countLeaves(child), 0);
    }
    return 1;
  }

  function nodeSymbol(type) {
    return ({ click: "⌖", swipe: "↗", wait: "◷", repeat: "↻", condition: "⑂", image: "▧", text: "T", sequence: "≡", log: "≡", stop: "■" })[type] || "•";
  }

  function listAt(parentId, slot) {
    if (!parentId) return flow.nodes;
    const found = findNode(parentId);
    if (!found) return null;
    return slot === "then" ? found.node.then : slot === "else" ? found.node.else : found.node.children;
  }

  function findNode(id, nodes = flow?.nodes || [], parentId = null, slot = "children") {
    for (let index = 0; index < nodes.length; index++) {
      const node = nodes[index];
      if (node.id === id) return { node, list: nodes, index, parentId, slot };
      for (const childSlot of ["children", "then", "else"]) {
        if (!node[childSlot]) continue;
        const found = findNode(id, node[childSlot], node.id, childSlot);
        if (found) return found;
      }
    }
    return null;
  }

  function containsNode(node, parentId) {
    if (!parentId) return false;
    if (node.id === parentId) return true;
    return ["children", "then", "else"].some(slot =>
      (node[slot] || []).some(child => containsNode(child, parentId))
    );
  }

  function dropZone(parentId, slot, index) {
    return `<div class="drop-zone" data-drop data-parent="${esc(parentId || "")}" data-slot="${slot}" data-index="${index}" title="插入模块"></div>`;
  }

  function renderList(nodes, parentId, slot, prefix) {
    return `<div class="flow-list" data-flow-list data-parent="${esc(parentId || "")}" data-slot="${slot}" data-length="${nodes.length}">${nodes.map((node, index) =>
      dropZone(parentId, slot, index) + renderNode(node, prefix ? `${prefix}.${index + 1}` : `${index + 1}`)
    ).join("")}${dropZone(parentId, slot, nodes.length)}</div>`;
  }

  function renderNode(node, number) {
    const isContainer = ["sequence", "repeat", "condition"].includes(node.type);
    const isExpanded = expanded.has(node.id);
    const title = nodeTitle(node);
    const asset = node.type === "image" ? (nodeProperties(node).asset || "") : "";
    const symbol = asset
      ? `<button class="node-symbol image node-thumbnail" data-preview-asset="${esc(asset)}" title="预览 ${esc(asset)}"><img src="${esc(assetPreviewUrl(asset))}" alt="${esc(asset)}"></button>`
      : `<span class="node-symbol ${node.type}">${nodeSymbol(node.type)}</span>`;
    let children = "";
    if (isContainer && isExpanded) {
      if (node.type === "condition") {
        children = `
          <div class="children">
            <div class="branch-label">✓ 条件成立</div>
            ${renderList(node.then || [], node.id, "then", `${number}.1`)}
            <button class="inline-add" data-inline-add data-parent="${node.id}" data-slot="then">＋ 添加成立时的步骤</button>
            <div class="branch-label">− 否则</div>
            ${renderList(node.else || [], node.id, "else", `${number}.2`)}
            <button class="inline-add" data-inline-add data-parent="${node.id}" data-slot="else">＋ 添加否则的步骤</button>
          </div>`;
      } else {
        children = `
          <div class="children">
            ${node.type === "repeat" ? '<div class="loop-label"><span>循环体</span><span>整组重复 ↺</span></div>' : ""}
            ${renderList(node.children || [], node.id, "children", number)}
            <button class="inline-add" data-inline-add data-parent="${node.id}" data-slot="children">＋ 添加到${node.type === "repeat" ? "循环体" : "顺序组"}</button>
          </div>`;
      }
    }
    const badge = node.type === "repeat"
      ? `<span class="node-badge">${(node.props?.mode || "count") === "count" ? `×${node.props?.count || 1}` : "循环"}</span>`
      : "";
    return `
      <div class="flow-node${node.enabled === false ? " disabled" : ""}${selectedId === node.id ? " selected" : ""}" data-type="${node.type}" data-node="${node.id}">
        <div class="flow-row">
          <div class="drag-handle" data-drag="${node.id}" title="拖动排序">⋮⋮</div>
          ${symbol}
          <button class="node-main" data-select="${node.id}">
            <span class="node-title"><span class="node-number">${number}</span>${esc(title)}</span>
            <span class="node-summary">${esc(nodeSummary(node))}</span>
          </button>
          ${badge}
          ${isContainer ? `<button class="node-fold" data-fold="${node.id}">${isExpanded ? "⌄" : "›"}</button>` : ""}
        </div>
        ${children}
      </div>`;
  }

  function renderFlowEditor() {
    const nodes = flow?.nodes || [];
    const previousInspector = dom.content.querySelector(".inspector");
    const inspectorScroll = previousInspector?.dataset.step === selectedId ? previousInspector.scrollTop : 0;
    dom.content.innerHTML = `
      <div class="flow-workspace${selectedId ? " has-selection" : ""}">
        <aside class="flow-library">
          <h3 class="panel-title">模块库</h3>
          <p class="panel-sub">插入到 ${targetLabel(insertTarget)}</p>
          ${renderCategoryTabs()}
          <div class="library-grid">${renderLibraryCards(category, false)}</div>
          <p class="panel-sub" style="margin-top:10px">拖到流程插入线，或点击添加；步骤可拖动左侧把手排序。</p>
        </aside>
        <section class="flow-canvas" tabindex="0" aria-label="流程画布">
          <div class="canvas-stage">
            <div class="boundary"><span class="boundary-dot"></span><span>开始</span><span class="boundary-line"></span><span>从上到下执行</span></div>
            ${nodes.length ? renderList(nodes, null, "children", "") : `
              <div class="empty-flow" data-drop data-parent="" data-slot="children" data-index="0">
                <div class="empty-plus">＋</div>
                <h3>开始搭建流程</h3>
                <p>从模块库添加步骤，支持顺序、循环、条件与屏幕识别。</p>
                <button class="primary-button" data-open-library style="margin-top:14px">添加第一个模块</button>
              </div>`}
            <div class="boundary end"><span class="boundary-dot"></span><span>本轮完成</span><span class="boundary-line"></span></div>
          </div>
          <div class="canvas-hint" aria-hidden="true">拖动空白平移 · Ctrl / ⌘ + 滚轮缩放</div>
          <div class="canvas-controls" data-canvas-controls role="group" aria-label="画布操作">
            <button class="canvas-mobile-add" data-open-library>＋ 模块</button>
            <button data-canvas-action="out" title="缩小" aria-label="缩小">−</button>
            <span class="canvas-zoom">100%</span>
            <button data-canvas-action="in" title="放大" aria-label="放大">＋</button>
            <button data-canvas-action="fit" title="适应画布">适应</button>
            <button data-canvas-action="reset" title="回到流程起点并恢复 100%">起点</button>
          </div>
        </section>
        <aside class="inspector" data-step="${esc(selectedId)}">${renderInspector()}</aside>
      </div>`;
    dom.content.querySelector(".inspector").scrollTop = inspectorScroll;
    const canvas = dom.content.querySelector(".flow-canvas");
    bindCanvas(canvas);
    applyCanvasView(canvas);
  }

  const minCanvasZoom = 0.08;
  const maxCanvasZoom = 2;

  function applyCanvasView(canvas) {
    const { x, y, zoom } = canvasView;
    canvas.style.setProperty("--canvas-x", `${x}px`);
    canvas.style.setProperty("--canvas-y", `${y}px`);
    canvas.style.setProperty("--canvas-zoom", zoom);
    canvas.style.setProperty("--grid-size", `${Math.max(10, 24 * zoom)}px`);
    canvas.querySelector(".canvas-zoom").textContent = `${Math.round(zoom * 100)}%`;
    canvas.querySelector('[data-canvas-action="out"]').disabled = zoom <= minCanvasZoom;
    canvas.querySelector('[data-canvas-action="in"]').disabled = zoom >= maxCanvasZoom;
  }

  function moveCanvas(canvas, dx, dy, factor = 1, anchorX, anchorY) {
    const bounds = canvas.getBoundingClientRect();
    const pivotX = (anchorX ?? bounds.left + bounds.width / 2) - bounds.left - bounds.width / 2;
    const pivotY = (anchorY ?? bounds.top + bounds.height / 2) - bounds.top;
    const zoom = Math.max(minCanvasZoom, Math.min(maxCanvasZoom, canvasView.zoom * factor));
    const ratio = zoom / canvasView.zoom;
    canvasView.x = pivotX - (pivotX - canvasView.x) * ratio + dx;
    canvasView.y = pivotY - (pivotY - canvasView.y) * ratio + dy;
    canvasView.zoom = zoom;
    applyCanvasView(canvas);
  }

  function canvasAction(canvas, action) {
    if (action === "in" || action === "out") {
      moveCanvas(canvas, 0, 0, action === "in" ? 1.2 : 1 / 1.2);
      return;
    }
    if (action === "fit") {
      const stage = canvas.querySelector(".canvas-stage");
      const zoom = Math.min(1, (canvas.clientWidth - 24) / stage.offsetWidth, (canvas.clientHeight - 96) / stage.offsetHeight);
      Object.assign(canvasView, { x: 0, y: 22, zoom: Math.max(minCanvasZoom, zoom) });
    } else {
      Object.assign(canvasView, { x: 0, y: 32, zoom: 1 });
    }
    applyCanvasView(canvas);
  }

  function revealCanvasNode(id) {
    const canvas = dom.content.querySelector(".flow-canvas");
    const row = canvas?.querySelector(`[data-node="${CSS.escape(id)}"] > .flow-row`);
    if (!row) return;
    const bounds = canvas.getBoundingClientRect();
    const node = row.getBoundingClientRect();
    const bottom = bounds.bottom - 62;
    if (node.top < bounds.top + 12 || node.bottom > bottom || node.right < bounds.left || node.left > bounds.right) {
      moveCanvas(canvas, bounds.left + bounds.width / 2 - (node.left + node.width / 2),
        bounds.top + (bottom - bounds.top) / 2 - (node.top + node.height / 2));
    }
  }

  function bindCanvas(canvas) {
    const pointers = new Map();
    let moved = false;
    let suppressClick = false;

    canvas.addEventListener("wheel", event => {
      if (busy || event.target.closest("[data-canvas-controls]")) return;
      event.preventDefault();
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? canvas.clientHeight : 1;
      if (event.ctrlKey || event.metaKey) {
        if (!drag) moveCanvas(canvas, 0, 0, Math.exp(-event.deltaY * unit * 0.002), event.clientX, event.clientY);
      } else {
        const sideways = event.shiftKey && !event.deltaX;
        moveCanvas(canvas, -(sideways ? event.deltaY : event.deltaX) * unit, sideways ? 0 : -event.deltaY * unit);
        if (drag?.active && drag.point) updateModuleDrop(drag.point.x, drag.point.y);
      }
    }, { passive: false });

    canvas.addEventListener("pointerdown", event => {
      if (busy || drag || event.target.closest("[data-canvas-controls]")) return;
      const touch = event.pointerType === "touch";
      const forced = !touch && (canvasPanKey || event.button === 1);
      if (event.button !== 0 && event.button !== 1) return;
      if (!forced && event.target.closest(touch ? "[data-drag]" : "button,[data-drop],[data-drag]")) return;
      if (pointers.size && (!touch || pointers.size >= 2)) return;
      if (!pointers.size) {
        moved = false;
        suppressClick = false;
      }
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      if (!touch || pointers.size > 1) {
        if (pointers.size > 1) moved = true;
        pointers.forEach((_, id) => canvas.setPointerCapture(id));
      }
      if (forced) event.preventDefault();
      event.stopPropagation();
    });

    canvas.addEventListener("pointermove", event => {
      const previous = pointers.get(event.pointerId);
      if (!previous) return;
      const dx = event.clientX - previous.x;
      const dy = event.clientY - previous.y;
      if (!moved && Math.hypot(dx, dy) < 4) return;
      moved = true;
      canvas.classList.add("panning");
      if (!canvas.hasPointerCapture(event.pointerId)) canvas.setPointerCapture(event.pointerId);
      const other = [...pointers].find(([id]) => id !== event.pointerId)?.[1];
      if (other) {
        const oldDistance = Math.hypot(previous.x - other.x, previous.y - other.y);
        const distance = Math.hypot(event.clientX - other.x, event.clientY - other.y);
        moveCanvas(canvas, dx / 2, dy / 2, distance / Math.max(oldDistance, 1),
          (previous.x + other.x) / 2, (previous.y + other.y) / 2);
      } else {
        moveCanvas(canvas, dx, dy);
      }
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      event.preventDefault();
    });

    function endPan(event) {
      if (!pointers.delete(event.pointerId)) return;
      if (moved) {
        suppressClick = true;
        setTimeout(() => { suppressClick = false; }, 0);
      }
      if (!pointers.size) canvas.classList.remove("panning");
    }
    canvas.addEventListener("pointerup", endPan);
    canvas.addEventListener("pointercancel", endPan);
    canvas.addEventListener("lostpointercapture", event => {
      if (event.target === canvas) endPan(event);
    });
    canvas.addEventListener("click", event => {
      if (!suppressClick) return;
      event.preventDefault();
      event.stopPropagation();
    }, true);
    canvas.addEventListener("auxclick", event => {
      if (event.button === 1) event.preventDefault();
    });
    canvas.addEventListener("keydown", event => {
      if (event.target !== canvas || busy) return;
      const arrows = { ArrowUp: [0, 48], ArrowDown: [0, -48], ArrowLeft: [48, 0], ArrowRight: [-48, 0] };
      if (arrows[event.key]) moveCanvas(canvas, ...arrows[event.key]);
      else if (event.key === "Home") canvasAction(canvas, "reset");
      else if (event.key === "+" || event.key === "=") canvasAction(canvas, "in");
      else if (event.key === "-") canvasAction(canvas, "out");
      else return;
      event.preventDefault();
    });
  }

  document.addEventListener("keydown", event => {
    if (event.code !== "Space" || mode !== "flow" || busy || !dom.modal.hidden ||
        (event.target !== document.body && !event.target.matches?.(".flow-canvas"))) return;
    canvasPanKey = true;
    event.preventDefault();
  });
  document.addEventListener("keyup", event => {
    if (event.code === "Space") canvasPanKey = false;
  });
  window.addEventListener("blur", () => { canvasPanKey = false; });

  function renderCategoryTabs() {
    return `<div class="segmented">${Object.keys(catalog).map(key =>
      `<button class="${category === key ? "active" : ""}" data-category="${key}">${categoryNames[key]}</button>`
    ).join("")}</div>`;
  }

  function renderLibraryCards(categoryKey, modal) {
    return catalog[categoryKey].map(def => {
      const index = catalogFlat.findIndex(item => item === def);
      const [rawType] = def[0].split(":");
      return `<div class="library-card" data-add-def="${index}">
        <button class="library-card-button" type="button" title="${esc(def[2])}">
          <span class="symbol ${rawType}">${nodeSymbol(rawType)}</span>
          <strong>${esc(def[1])}</strong><small>${esc(def[2])}</small>
        </button>
        ${modal ? "" : '<span class="library-drag" data-library-drag title="拖动模块" aria-hidden="true">⠿</span>'}
      </div>`;
    }).join("");
  }

  function targetLabel(target) {
    if (!target.parentId) return "主流程末尾";
    const found = findNode(target.parentId);
    const scope = target.slot === "then" ? "条件成立" : target.slot === "else" ? "否则" : "容器内部";
    return `${found?.node?.title || "当前容器"} · ${scope}`;
  }

  function renderInspector() {
    const found = selectedId ? findNode(selectedId) : null;
    if (!found) {
      return `<h3 class="panel-title">步骤参数</h3><p class="panel-sub">选择流程中的步骤或容器进行配置。</p>
        <div class="inspector-empty">找图与找字默认仅识别，可单独选择“点击命中位置”。</div>`;
    }
    const node = found.node;
    const p = nodeProperties(node);
    let fields = "";
    if (["click", "swipe"].includes(node.type)) {
      fields += fieldPair(
        numberField(node.type === "swipe" ? "起点 X" : "X 坐标", "x", p.x || 0),
        numberField(node.type === "swipe" ? "起点 Y" : "Y 坐标", "y", p.y || 0)
      );
      if (node.type === "swipe") {
        fields += fieldPair(numberField("终点 X", "endX", p.endX || 0), numberField("终点 Y", "endY", p.endY || 0));
      }
      fields += numberField(node.type === "swipe" ? "滑动时长 ms" : "按下时长 ms", "duration", p.duration || 0);
      fields += choiceField("操作失败时", "failure", [["stop", "停止报错"], ["skip", "记录跳过"]], p.failure || "stop");
    }
    if (node.type === "wait") fields += numberField("等待时间 ms", "duration", p.duration || 1000);
    if (node.type === "log") {
      fields += textField("日志内容", "text", p.text || "");
      fields += choiceField("日志等级", "level", [["info", "信息"], ["warn", "警告"], ["error", "错误"]], p.level || "info");
    }
    if (node.type === "repeat") {
      fields += choiceField("循环方式", "mode", [
        ["count", "按次数"], ["while", "条件成立"], ["until", "直到满足"], ["forever", "持续"]
      ], p.mode || "count");
      if ((p.mode || "count") === "count") {
        fields += numberField("重复次数", "count", p.count || 1);
      } else if (["while", "until"].includes(p.mode)) {
        fields += conditionFields(p);
        fields += fieldPair(
          numberField("最多执行次数", "maxIterations", p.maxIterations || 0),
          numberField("最长时长 ms", "maxDuration", p.maxDuration || 0)
        );
      }
      fields += numberField("轮间隔 ms", "interval", p.interval || 0);
    }
    if (node.type === "condition") fields += conditionFields(p);
    if (node.type === "image") {
      fields += assetField(p.asset || "");
      fields += numberField("匹配阈值 %", "thresholdPercent", Math.round((p.threshold ?? 0.9) * 100));
    }
    if (node.type === "text") {
      fields += textField("查找文字", "text", p.text || "");
      fields += choiceField("匹配方式", "match", [["contains", "包含"], ["exact", "完全相同"]], p.match || "contains");
    }
    if (["image", "text"].includes(node.type)) {
      fields += textField("识别区域 [X,Y,宽,高]", "region", p.region || "");
      fields += fieldPair(numberField("等待超时 ms", "timeout", p.timeout || 5000), numberField("轮询间隔 ms", "poll", p.poll || 300));
      fields += choiceField("命中后", "onMatch", [["recognize", "仅识别"], ["click", "点击命中位置"]], p.onMatch || "recognize");
      fields += choiceField("未命中时", "onMiss", [["continue", "继续执行"], ["stop", "停止报错"]], p.onMiss || "continue");
      if ((p.onMatch || "recognize") === "click") {
        fields += choiceField("点击失败时", "failure", [["stop", "停止报错"], ["skip", "记录跳过"]], p.failure || "stop");
      }
    }
    return `
      <h3 class="panel-title">步骤参数</h3>
      <p class="panel-sub">${esc(nodeTitle(node))} · ${esc(node.type)}</p>
      <label class="field"><span>步骤名称</span><input class="input" data-title value="${esc(node.title)}"></label>
      <label class="switch-line"><span>启用此步骤</span><input type="checkbox" data-enabled ${node.enabled === false ? "" : "checked"}></label>
      ${fields}
      <div class="inspector-actions">
        <button data-move="-1" ${found.index <= 0 ? "disabled" : ""}>上移</button>
        <button data-move="1" ${found.index >= found.list.length - 1 ? "disabled" : ""}>下移</button>
        <button data-duplicate>复制</button>
        <button class="danger" data-delete>删除</button>
      </div>`;
  }

  function textField(label, key, value) {
    return `<label class="field"><span>${label}</span><input class="input" data-prop="${key}" value="${esc(value)}"></label>`;
  }

  function numberField(label, key, value) {
    return `<label class="field"><span>${label}</span><input class="input" type="number" min="0" data-prop="${key}" data-number="true" value="${esc(value)}"></label>`;
  }

  function fieldPair(first, second) {
    return `<div class="field-pair">${first}${second}</div>`;
  }

  function choiceField(label, key, options, selected) {
    return `<div class="field"><span>${label}</span><div class="choice-grid">${options.map(([value, text]) =>
      `<button class="choice${selected === value ? " active" : ""}" data-choice-key="${key}" data-choice-value="${value}">${text}</button>`
    ).join("")}</div></div>`;
  }

  function conditionFields(p) {
    const combined = ["all", "any"].includes(p.combine);
    const firstKind = p.conditionKind || "image";
    const secondKind = p.secondKind || "text";
    let html = choiceField("条件组合", "combine", [
      ["single", "单个"], ["all", "全部满足"], ["any", "任一满足"], ["not", "条件不满足"]
    ], p.combine || "single");
    html += choiceField("检测方式", "conditionKind", [["image", "图片存在"], ["text", "文字存在"]], firstKind);
    html += firstKind === "image"
      ? assetField(p.conditionValue, "conditionValue", combined ? "第一项图片" : "条件图片")
      : textField("识别文字", "conditionValue", p.conditionValue || "");
    if (combined) {
      html += choiceField("第二项", "secondKind", [["image", "图片"], ["text", "文字"]], secondKind);
      html += secondKind === "image"
        ? assetField(p.secondValue, "secondValue", "第二项图片")
        : textField("第二项文字", "secondValue", p.secondValue || "");
    }
    return html;
  }

  function assetField(asset, key = "asset", label = "图片素材") {
    const ref = String(asset ?? "");
    const name = ref.replace(/^images[\\/]/, "");
    return `<div class="field"><span>${label}</span><div class="asset-box${key === "asset" ? "" : " condition-asset"}">
      ${ref ? `<button class="asset-preview" data-preview-asset="${esc(ref)}" title="查看图片预览">
        <img src="${esc(assetPreviewUrl(ref))}" alt="${esc(name)}">
        <span>点击查看大图</span>
      </button>` : ""}
      <div class="asset-name" title="${esc(name)}">${name ? esc(name) : "未选择图片"}</div>
      <div class="asset-actions">
        <button class="secondary-button" data-select-asset="${key}">从素材选择</button>
        <button class="secondary-button" data-pick-image="${key}">上传图片</button>
        ${ref ? `<button class="secondary-button" data-remove-image="${key}">移除</button>` : ""}
      </div>
    </div></div>`;
  }

  function openLibrary(target = insertTarget) {
    insertTarget = target;
    if (window.innerWidth > 900) {
      renderFlowEditor();
      toast(`插入位置：${targetLabel(target)}`);
      return;
    }
    showModal(`
      <div class="modal-head">
        <div><h3>添加模块</h3><p>插入到 ${esc(targetLabel(target))}</p></div>
        <button class="modal-close" data-close-modal>×</button>
      </div>
      <div class="modal-body">
        ${renderCategoryTabs()}
        <div class="library-modal-grid" style="margin-top:12px">${renderLibraryCards(category, true)}</div>
      </div>`);
  }

  function addModule(defIndex, targetOverride = null) {
    const definition = catalogFlat[defIndex];
    if (!definition) return null;
    const node = newNode(definition);
    const target = { ...(targetOverride || insertTarget) };
    const list = listAt(target.parentId, target.slot);
    if (!list) return null;
    const index = target.index === Number.MAX_SAFE_INTEGER ? list.length : Math.min(target.index, list.length);
    list.splice(index, 0, node);
    if (target.parentId) expanded.add(target.parentId);
    expanded.add(node.id);
    selectedId = node.id;
    setDirty(true);
    closeModal();
    renderFlowEditor();
    revealCanvasNode(node.id);
    toast(`已添加「${nodeTitle(node)}」`);
    return node;
  }

  function moveNode(id, target) {
    const found = findNode(id);
    if (!found || containsNode(found.node, target.parentId)) return false;
    let destinationIndex = target.index;
    if (found.parentId === target.parentId && found.slot === target.slot && found.index < destinationIndex) {
      destinationIndex--;
    }
    found.list.splice(found.index, 1);
    const destination = listAt(target.parentId, target.slot);
    if (!destination) return false;
    destination.splice(Math.max(0, Math.min(destinationIndex, destination.length)), 0, found.node);
    setDirty(true);
    return true;
  }

  function duplicateNode(id) {
    const found = findNode(id);
    if (!found) return;
    const copy = deepCopyWithIds(found.node);
    found.list.splice(found.index + 1, 0, copy);
    selectedId = copy.id;
    expanded.add(copy.id);
    setDirty(true);
    renderFlowEditor();
  }

  function deepCopyWithIds(node) {
    return {
      ...JSON.parse(JSON.stringify(node)),
      id: uid(),
      children: node.children?.map(deepCopyWithIds),
      then: node.then?.map(deepCopyWithIds),
      else: node.else?.map(deepCopyWithIds)
    };
  }

  async function saveCurrent() {
    if (!current || !dirtyModes.has(mode)) return;
    if (mode === "script" && dirtyModes.has("flow") &&
        !confirm("保存脚本会转换任务类型，并放弃未保存的流程修改。确定保存吗？")) return;
    await perform(async () => {
      if (mode === "script") {
        await api(`${taskPath()}/script`, {
          method: "PUT",
          body: JSON.stringify({ source: current.editorSource ?? current.source })
        });
        current.type = "script";
        current.source = current.editorSource ?? current.source;
        current.scriptSource = null;
        flow = null;
        dirtyModes.delete("flow");
      } else if (mode === "flow" && flow) {
        flow.schemaVersion = 1;
        flow.globalCount = current.globalCount;
        const result = await api(`${taskPath()}/flow`, {
          method: "PUT",
          body: JSON.stringify(flow)
        });
        current.source = JSON.stringify(flow);
        current.scriptSource = result.scriptSource;
        if (!dirtyModes.has("script")) current.editorSource = null;
      } else {
        throw new Error("当前页面没有可保存的编辑内容");
      }
      setDirty(false);
      renderEditor();
      toast("已保存");
      await loadTasks();
    });
  }

  async function showCodePreview() {
    try {
      if (mode === "flow" && flow) {
        const result = await api(`/api/tasks/${encodeURIComponent(current.name)}/validate`, {
          method: "POST",
          body: JSON.stringify(flow)
        });
        if (!result.ok) throw new Error(result.error || "流程校验失败");
        showCodeModal(result.code);
      } else if (current.type === "flow" && mode === "script") {
        showCodeModal(current.editorSource ?? current.scriptSource ?? current.source);
      } else if (current.type === "flow") {
        const result = await api(`/api/tasks/${encodeURIComponent(current.name)}/validate`, {
          method: "POST",
          body: JSON.stringify(flow)
        });
        if (!result.ok) throw new Error(result.error || "流程校验失败");
        showCodeModal(result.code);
      } else {
        showCodeModal(current.editorSource ?? current.source);
      }
    } catch (error) {
      toast(error.message, true);
    }
  }

  function showCodeModal(code) {
    showModal(`
      <div class="modal-head">
        <div><h3>代码预览</h3><p>task.js · 只读</p></div>
        <button class="modal-close" data-close-modal>×</button>
      </div>
      <div class="modal-body"><pre class="code-preview">${esc(code)}</pre></div>`, true);
  }

  function showModal(html, wide = false) {
    dom.modal.hidden = false;
    dom.modal.innerHTML = `<div class="modal${wide ? " wide" : ""}">${html}</div>`;
  }

  function closeModal() {
    dom.modal.hidden = true;
    dom.modal.innerHTML = "";
  }

  function taskSettingsModal() {
    showModal(`
      <div class="modal-head">
        <div><h3>任务设置</h3><p>${esc(current.name)}</p></div>
        <button class="modal-close" data-close-modal aria-label="关闭">×</button>
      </div>
      <form id="task-settings-form">
        <div class="modal-body settings-body">
          <label class="field"><span>任务名称</span><input class="input" name="name" required maxlength="50" value="${esc(current.name)}"></label>
          <label class="field"><span>描述</span><textarea class="input" name="description" rows="3">${esc(current.description)}</textarea></label>
          <label class="field"><span>目标应用包名</span><input class="input" name="target" placeholder="例如：com.example.app" value="${esc(current.target)}"></label>
          <div class="field-pair">
            <label class="field"><span>整任务运行方式</span><select class="input" name="loopMode">
              <option value="count">按次数</option><option value="duration">按时长</option><option value="untilStopped">直到手动停止</option>
            </select></label>
            <label class="field" data-loop-field="count"><span>执行次数</span><input class="input" name="globalCount" type="number" min="1" max="2147483647" required value="${current.globalCount}"></label>
            <label class="field" data-loop-field="duration"><span>运行时长（秒）</span><input class="input" name="durationSec" type="number" min="1" max="2147483647" required value="${current.durationSec}"></label>
          </div>
          <label class="field"><span>每轮间隔（秒）</span><input class="input" name="intervalSec" type="number" min="0" max="2147483647" required value="${current.intervalSec}"></label>
          <div class="field-pair">
            <label class="field"><span>设计宽度</span><input class="input" name="designWidth" type="number" min="1" max="2147483647" placeholder="未设置" value="${current.designWidth || ""}"></label>
            <label class="field"><span>设计高度</span><input class="input" name="designHeight" type="number" min="1" max="2147483647" placeholder="未设置" value="${current.designHeight || ""}"></label>
          </div>
          <p class="settings-hint">设计尺寸用于坐标缩放，宽度和高度可同时留空。</p>
        </div>
        <div class="modal-actions settings-actions">
          <button class="secondary-button danger" type="button" id="delete-task">删除任务</button>
          <button class="secondary-button" type="button" data-close-modal>取消</button>
          <button class="primary-button" type="submit">保存设置</button>
        </div>
      </form>`);
    const form = dom.modal.querySelector("form");
    const field = name => form.elements.namedItem(name);
    field("loopMode").value = current.loopMode;
    function showLoopField() {
      form.querySelectorAll("[data-loop-field]").forEach(label => {
        const visible = label.dataset.loopField === field("loopMode").value;
        label.hidden = !visible;
        label.querySelector("input").disabled = !visible;
      });
    }
    field("loopMode").onchange = showLoopField;
    showLoopField();
    form.onsubmit = event => {
      event.preventDefault();
      if (!field("name").value.trim()) return toast("请输入任务名称", true);
      const settings = Object.fromEntries(new FormData(form));
      for (const key of ["globalCount", "durationSec", "intervalSec", "designWidth", "designHeight"]) {
        settings[key] = key in settings ? Number(settings[key]) : current[key];
      }
      if (Boolean(settings.designWidth) !== Boolean(settings.designHeight)) {
        return toast("请同时填写设计宽度和高度", true);
      }
      perform(async () => {
        const result = await api(taskPath(), { method: "PUT", body: JSON.stringify(settings) });
        const renamed = result.name !== current.name;
        if (renamed && dom.search.value) dom.search.value = "";
        Object.assign(current, result);
        closeModal();
        renderEditor();
        toast(renamed ? "已重命名并保存设置" : "任务设置已保存");
        await loadTasks();
      });
    };
    dom.modal.querySelector("#delete-task").onclick = () => {
      if (!confirm(`删除「${current.name}」？脚本、流程、素材和定时设置将一并移除，无法恢复。`)) return;
      perform(async () => {
        await api(taskPath(), { method: "DELETE" });
        closeModal();
        current = flow = assets = null;
        dirtyModes.clear();
        dom.editor.hidden = true;
        dom.empty.hidden = false;
        toast("任务已删除");
        await loadTasks();
      });
    };
  }

  function assetDetails(asset) {
    const size = asset.size >= 1024 * 1024 ? `${(asset.size / (1024 * 1024)).toFixed(1)} MB`
      : asset.size >= 1024 ? `${(asset.size / 1024).toFixed(1)} KB` : `${asset.size} B`;
    return asset.width > 0 && asset.height > 0 ? `${asset.width} × ${asset.height} · ${size}` : size;
  }

  function renderAssets() {
    const historyView = assetView === "history";
    dom.content.innerHTML = `
      <section class="assets-workspace">
        <div class="assets-header">
          <div><h3>${historyView ? "历史素材" : "任务素材"}</h3>
            <p>${historyView ? "查看裁剪前保留的原图" : assets ? `${assets.length} 张图片` : ""}</p>
          </div>
          ${historyView ? "" : '<button class="primary-button" data-upload-assets>上传图片</button>'}
        </div>
        <div class="assets-tabs" role="group" aria-label="素材分类">
          <button class="choice ${historyView ? "" : "active"}" data-asset-view="current" aria-pressed="${!historyView}">当前素材</button>
          <button class="choice ${historyView ? "active" : ""}" data-asset-view="history" aria-pressed="${historyView}">历史素材</button>
        </div>
        ${historyView ? renderHistory() : renderAssetGrid()}
      </section>`;
  }

  function renderAssetGrid() {
    return `
        ${assetsError ? `<div class="assets-empty"><p>${esc(assetsError)}</p><button class="secondary-button" data-reload-assets>重试</button></div>`
        : !assets ? '<div class="assets-empty" role="status">正在加载素材…</div>'
        : !assets.length ? '<div class="assets-empty">还没有素材。上传图片后即可在流程中使用。</div>'
        : `<div class="assets-grid">${assets.map(asset => `
            <article class="asset-card">
              <button class="asset-card-preview" data-preview-asset="${esc(asset.name)}" aria-label="预览 ${esc(asset.name)}">
                <img src="${esc(assetPreviewUrl(asset.name, asset.version))}" alt="${esc(asset.name)}" loading="lazy">
              </button>
              <div class="asset-card-info">
                <strong>${esc(asset.name)}</strong><p>${assetDetails(asset)}</p>
                <div class="asset-card-actions">
                  <button class="secondary-button" data-crop-asset="${esc(asset.name)}">裁剪</button>
                  <button class="secondary-button" data-rename-asset="${esc(asset.name)}">重命名</button>
                  <a class="secondary-button" href="${esc(assetPreviewUrl(asset.name, asset.version))}" download="${esc(asset.name)}">下载</a>
                  <button class="secondary-button danger" data-delete-asset="${esc(asset.name)}">删除</button>
                </div>
              </div>
            </article>`).join("")}</div>`}`;
  }

  function renderHistory() {
    if (historyError) return `<div class="assets-empty"><p>${esc(historyError)}</p><button class="secondary-button" data-reload-assets>重试</button></div>`;
    if (!imageHistory) return '<div class="assets-empty" role="status">正在加载历史素材…</div>';
    if (!imageHistory.length) return '<div class="assets-empty">暂无历史素材。裁剪前的原图会保存在这里。</div>';
    return `<div class="history-list">
      ${imageHistory.map(entry => `
        <div class="history-entry">
          <button class="history-thumb" data-preview-history="${esc(entry.id)}" aria-label="预览 ${esc(entry.name)}" title="查看原图">
            <img src="${esc(historyAssetUrl(entry.id))}" alt="" loading="lazy">
          </button>
          <div class="history-info"><strong>${esc(entry.name)}</strong><p>${esc(entry.archivedAt)}</p></div>
          <button class="secondary-button" data-restore-asset="${esc(entry.id)}">移回素材</button>
        </div>`).join("")}
    </div>`;
  }

  async function loadImageHistory() {
    try {
      const result = await api(`${taskPath()}/image-history`);
      imageHistory = result.entries || [];
      historyError = "";
    } catch (error) {
      imageHistory = null;
      historyError = error.message;
    }
    if (mode === "assets" && assetView === "history") renderAssets();
  }

  function refreshAssetView() {
    return assetView === "history" ? loadImageHistory() : loadAssets();
  }

  function restoreAsset(id) {
    const entry = imageHistory?.find(item => item.id === id);
    if (!entry || !confirm(`移回「${entry.name}」？如果当前素材中有同名图片，将直接覆盖。`)) return;
    perform(async () => {
      const result = await api(`${historyAssetUrl(id)}/restore`, { method: "POST" });
      closeModal();
      assetRevision++;
      await Promise.all([loadAssets(), loadImageHistory(), loadTasks()]);
      toast(`已移回 ${result.name}`);
    });
  }

  function openCrop(name) {
    perform(async () => {
      const path = `${assetUrl(name)}/crop`;
      const response = await fetch(path);
      if (!response.ok) {
        const failure = await response.json().catch(() => ({}));
        throw new Error(failure.error || "无法读取裁剪预览");
      }
      const width = Number(response.headers.get("X-Image-Width"));
      const height = Number(response.headers.get("X-Image-Height"));
      const version = response.headers.get("X-Image-Version");
      if (!(width > 0 && height > 0 && version)) throw new Error("无法读取原图尺寸");
      const url = URL.createObjectURL(await response.blob());
      const image = new Image();
      image.alt = name;
      image.draggable = false;
      image.src = url;
      try {
        await image.decode();
        cropModal(name, path, image, width, height, version);
      } finally {
        URL.revokeObjectURL(url);
      }
    });
  }

  function cropModal(name, path, image, width, height, version) {
    showModal(`
      <div class="modal-head">
        <div><h3>裁剪图片</h3><p>${esc(name)} · ${width} × ${height} px</p></div>
        <button class="modal-close" data-close-modal aria-label="关闭">×</button>
      </div>
      <form class="crop-form">
        <div class="modal-body crop-body">
          <p>在图上拖动框选。拖动选区可移动，拖动四角可调整大小。</p>
          <div class="crop-board">
            <div class="crop-stage">
              <div class="crop-selection" hidden>
                ${["nw", "ne", "sw", "se"].map(corner => `<span class="crop-handle" data-corner="${corner}"></span>`).join("")}
              </div>
            </div>
          </div>
          <div class="crop-controls">
            <div class="crop-fields">
              ${[["x", "X", 0, width - 1], ["y", "Y", 0, height - 1], ["w", "宽度", 1, width], ["h", "高度", 1, height]].map(([key, label, min, max]) =>
                `<label class="field"><span>${label}</span><input class="input" name="${key}" type="number" min="${min}" max="${max}" step="1" placeholder="—"></label>`).join("")}
            </div>
            <div class="crop-tools">
              <button type="button" class="secondary-button" data-crop-reset>重选</button>
              <button type="button" class="secondary-button" data-crop-all>全选</button>
            </div>
          </div>
          <p>保存后替换当前图片，原图移入历史素材，脚本引用的文件名不变。</p>
        </div>
        <div class="modal-actions">
          <button type="button" class="secondary-button" data-close-modal>取消</button>
          <button type="submit" class="primary-button" disabled>保存裁剪</button>
        </div>
      </form>`, true);
    const form = dom.modal.querySelector(".crop-form");
    const stage = form.querySelector(".crop-stage");
    const outline = stage.querySelector(".crop-selection");
    const save = form.querySelector('[type="submit"]');
    const inputs = ["x", "y", "w", "h"].map(key => form.elements.namedItem(key));
    const previewWidth = Math.max(width, Math.min(440, Math.max(160, width * 8)));
    stage.style.setProperty("--crop-width", `${previewWidth}px`);
    stage.style.setProperty("--crop-ratio", String(width / height));
    stage.style.aspectRatio = `${width} / ${height}`;
    if (width < 180 && height < 180) stage.classList.add("crop-pixelated");
    stage.prepend(image);

    let region = null;
    let gesture = null;
    const clamp = (value, max) => Math.max(0, Math.min(value, max));
    function setRegion(next, updateFields = true) {
      region = next;
      outline.hidden = !region;
      save.disabled = !region;
      if (region) {
        const { left, top, right, bottom } = region;
        outline.style.left = `${100 * left / width}%`;
        outline.style.top = `${100 * top / height}%`;
        outline.style.width = `${100 * (right - left) / width}%`;
        outline.style.height = `${100 * (bottom - top) / height}%`;
      }
      if (updateFields) {
        const values = region ? [region.left, region.top, region.right - region.left, region.bottom - region.top] : ["", "", "", ""];
        inputs.forEach((input, index) => input.value = values[index]);
      }
    }
    function point(event) {
      const bounds = stage.getBoundingClientRect();
      return {
        x: Math.round((event.clientX - bounds.left) * width / bounds.width),
        y: Math.round((event.clientY - bounds.top) * height / bounds.height)
      };
    }
    function drawBetween(x1, y1, x2, y2) {
      if (x1 === x2 || y1 === y2) return setRegion(null);
      setRegion({ left: Math.min(x1, x2), top: Math.min(y1, y2), right: Math.max(x1, x2), bottom: Math.max(y1, y2) });
    }
    stage.onpointerdown = event => {
      if (busy || gesture || !event.isPrimary || event.button !== 0) return;
      const start = point(event);
      const corner = event.target.closest(".crop-handle")?.dataset.corner;
      const moving = !corner && event.target === outline;
      gesture = { id: event.pointerId, start, initial: region, corner, moving, changed: false };
      stage.setPointerCapture(event.pointerId);
      event.preventDefault();
    };
    function updateGesture(event) {
      if (gesture?.id !== event.pointerId) return;
      const { start, initial, corner, moving } = gesture;
      const { x, y } = point(event);
      if (!gesture.changed && x === start.x && y === start.y) return;
      gesture.changed = true;
      if (moving && initial) {
        const w = initial.right - initial.left, h = initial.bottom - initial.top;
        const left = clamp(initial.left + x - start.x, width - w);
        const top = clamp(initial.top + y - start.y, height - h);
        setRegion({ left, top, right: left + w, bottom: top + h });
      } else if (corner && initial) {
        const west = corner.endsWith("w"), north = corner.startsWith("n");
        const edgeX = (west ? initial.left : initial.right) + x - start.x;
        const edgeY = (north ? initial.top : initial.bottom) + y - start.y;
        setRegion({
          left: west ? clamp(edgeX, initial.right - 1) : initial.left,
          right: west ? initial.right : Math.max(initial.left + 1, clamp(edgeX, width)),
          top: north ? clamp(edgeY, initial.bottom - 1) : initial.top,
          bottom: north ? initial.bottom : Math.max(initial.top + 1, clamp(edgeY, height))
        });
      } else {
        drawBetween(clamp(start.x, width), clamp(start.y, height), clamp(x, width), clamp(y, height));
      }
    }
    stage.onpointermove = updateGesture;
    stage.onpointerup = event => {
      updateGesture(event);
      if (gesture?.id === event.pointerId) gesture = null;
    };
    stage.onlostpointercapture = event => {
      if (gesture?.id !== event.pointerId) return;
      setRegion(gesture.initial);
      gesture = null;
    };
    for (const input of inputs) input.oninput = () => {
      const [x, y, w, h] = inputs.map(field => field.valueAsNumber);
      const valid = [x, y, w, h].every(Number.isInteger) && x >= 0 && y >= 0 &&
        w > 0 && h > 0 && x + w <= width && y + h <= height;
      setRegion(valid ? { left: x, top: y, right: x + w, bottom: y + h } : null, false);
    };
    form.querySelector("[data-crop-all]").onclick = () => setRegion({ left: 0, top: 0, right: width, bottom: height });
    form.querySelector("[data-crop-reset]").onclick = () => setRegion(null);
    form.onsubmit = event => {
      event.preventDefault();
      if (!region) return;
      perform(async () => {
        await api(path, { method: "POST", body: JSON.stringify({ ...region, version }) });
        closeModal();
        assetRevision++;
        imageHistory = null;
        if (mode === "assets") await loadAssets();
        else if (mode === "flow") renderFlowEditor();
        toast("裁剪已保存，原图已移入历史素材");
      });
    };
  }

  async function loadAssets() {
    try {
      const result = await api(`${taskPath()}/images`, { cache: "no-store" });
      assets = result.images || [];
      assetsError = "";
    } catch (error) {
      assets = null;
      assetsError = error.message;
    }
    if (mode === "assets" && assetView === "current") renderAssets();
  }

  function renameAssetModal(name, proposed = name) {
    if (dirtyModes.size) return toast("请先保存脚本或流程修改，再重命名素材", true);
    showModal(`
      <div class="modal-head">
        <div><h3>重命名素材</h3><p>${esc(name)}</p></div>
        <button class="modal-close" data-close-modal aria-label="关闭">×</button>
      </div>
      <form id="asset-rename-form">
        <div class="modal-body settings-body">
          <label class="field"><span>新文件名</span><input class="input" name="name" value="${esc(proposed)}" required></label>
          <p class="settings-hint">确认前可以查看脚本和流程中受影响的引用。</p>
        </div>
        <div class="modal-actions">
          <button class="secondary-button" type="button" data-close-modal>取消</button>
          <button class="primary-button" type="submit">查看引用</button>
        </div>
      </form>`);
    const form = dom.modal.querySelector("form");
    const input = form.elements.namedItem("name");
    input.focus();
    form.onsubmit = event => {
      event.preventDefault();
      const newName = input.value.trim();
      if (!newName) return toast("请输入文件名", true);
      if (newName === name) return closeModal();
      perform(async () => {
        const plan = await api(`${assetUrl(name)}/rename-preview`, { method: "POST", body: JSON.stringify({ name: newName }) });
        confirmAssetRename(name, plan);
      });
    };
  }

  function confirmAssetRename(name, plan) {
    const lines = plan.lines || [];
    const files = plan.flowFiles || [];
    showModal(`
      <div class="modal-head">
        <div><h3>确认素材重命名</h3><p>${esc(name)} → ${esc(plan.name)}</p></div>
        <button class="modal-close" data-close-modal aria-label="关闭">×</button>
      </div>
      <div class="modal-body rename-review">
        <p>${plan.scriptReferences ? `将同步更新 task.js 的 ${plan.scriptReferences} 处引用（${plan.scriptLines} 行）。`
          : "task.js 中没有匹配的文件名引用。"} </p>
        ${files.length ? `<p>同时更新流程引用：${esc(files.join("、"))}</p>` : ""}
        ${lines.length ? `<pre class="code-preview">${lines.map(line =>
          `${line.line}  - ${esc(line.before)}\n${line.line}  + ${esc(line.after)}`).join("\n\n")}</pre>` : ""}
        ${plan.scriptLines > lines.length ? `<p>仅展示前 ${lines.length} 行，保存时会同步更新全部匹配项。</p>` : ""}
      </div>
      <div class="modal-actions">
        <button class="secondary-button" data-rename-back>返回修改</button>
        <button class="primary-button" data-rename-confirm>确认重命名</button>
      </div>`, lines.length > 0);
    dom.modal.querySelector("[data-rename-back]").onclick = () => renameAssetModal(name, plan.name);
    dom.modal.querySelector("[data-rename-confirm]").onclick = () => perform(async () => {
      const result = await api(assetUrl(name), { method: "PUT", body: JSON.stringify({ name: plan.name }) });
      closeModal();
      const [data] = await Promise.all([api(taskPath()), loadAssets()]);
      current = data;
      flow = data.type === "flow" ? JSON.parse(data.source) : null;
      renderEditor();
      const synced = [
        result.scriptReferences ? `脚本 ${result.scriptReferences} 处引用` : "",
        result.flowFiles?.length ? "流程引用" : ""
      ].filter(Boolean);
      toast(`已重命名为 ${result.name}${synced.length ? `，已同步${synced.join("、")}` : ""}`);
    });
  }

  function deleteAsset(name) {
    if (!confirm(`删除素材「${name}」？使用该图片的脚本或流程将无法读取它。`)) return;
    perform(async () => {
      await api(assetUrl(name), { method: "DELETE" });
      await loadAssets();
      toast("素材已删除");
      await loadTasks();
    });
  }

  function createTaskModal() {
    showModal(`
      <div class="modal-head">
        <div><h3>新建任务</h3><p>创建后可立即在浏览器中编辑</p></div>
        <button class="modal-close" data-close-modal>×</button>
      </div>
      <div class="modal-body">
        <label class="field"><span>任务名称</span><input class="input" id="new-task-name" maxlength="50" placeholder="例如：每日签到"></label>
        <div class="field"><span>任务类型</span>
          <div class="choice-grid">
            <button class="choice active" data-new-type="script">JavaScript 脚本</button>
            <button class="choice" data-new-type="flow">可视化流程</button>
          </div>
        </div>
      </div>
      <div class="modal-actions">
        <button class="secondary-button" data-close-modal>取消</button>
        <button class="primary-button" id="confirm-create-task">创建</button>
      </div>`);
    let type = "script";
    dom.modal.querySelectorAll("[data-new-type]").forEach(button => {
      button.onclick = () => {
        type = button.dataset.newType;
        dom.modal.querySelectorAll("[data-new-type]").forEach(item => item.classList.toggle("active", item === button));
      };
    });
    dom.modal.querySelector("#confirm-create-task").onclick = async () => {
      const name = dom.modal.querySelector("#new-task-name").value.trim();
      if (!name) return toast("请输入任务名称", true);
      try {
        await api("/api/tasks", { method: "POST", body: JSON.stringify({ name, type }) });
        closeModal();
        await loadTasks();
        await openTask(name);
      } catch (error) {
        toast(error.message, true);
      }
    };
    setTimeout(() => dom.modal.querySelector("#new-task-name")?.focus(), 0);
  }

  function updateNodeProperty(key, value, rerender = true) {
    const found = findNode(selectedId);
    if (!found) return;
    if (key === "thresholdPercent") {
      key = "threshold";
      value = Math.max(0, Math.min(100, Number(value) || 0)) / 100;
    }
    setNodeProperty(found.node, key, value);
    if (rerender) renderFlowEditor();
  }

  function setNodeProperty(node, key, value) {
    (node.props ||= {})[key] = value;
    // Older flows store parameters on the node; remove the old value so it can't override the edit.
    delete node[key];
    setDirty(true);
  }

  function chooseImage(nodeId, key) {
    pendingImageNode = { task: current, nodeId, key };
    dom.imageInput.value = "";
    dom.imageInput.click();
  }

  function assignNodeAsset(node, key, name) {
    setNodeProperty(node, key, name);
    renderFlowEditor();
  }

  function chooseTaskAsset(nodeId, key) {
    perform(async () => {
      await loadAssets();
      if (!assets) throw new Error(assetsError);
      if (!assets.length) return toast("当前任务没有图片，请先上传");
      showModal(`
        <div class="modal-head">
          <div><h3>选择任务素材</h3><p>${key === "asset" ? "找图步骤" : key === "secondValue" ? "第二项图片条件" : "第一项图片条件"}</p></div>
          <button class="modal-close" data-close-modal aria-label="关闭">×</button>
        </div>
        <div class="modal-body assets-grid">${assets.map(asset => `
          <button class="asset-card asset-choice" data-use-asset="${esc(asset.name)}">
            <img src="${esc(assetPreviewUrl(asset.name, asset.version))}" alt="" loading="lazy">
            <strong>${esc(asset.name)}</strong>
          </button>`).join("")}</div>`, true);
      dom.modal.querySelectorAll("[data-use-asset]").forEach(button => {
        button.onclick = () => {
          const found = findNode(nodeId);
          if (!found) return;
          assignNodeAsset(found.node, key, button.dataset.useAsset);
          closeModal();
        };
      });
    });
  }

  async function uploadAsset(file) {
    if (file.size > 15 * 1024 * 1024) throw new Error(`${file.name} 超过 15 MB`);
    const data = await fileToBase64(file);
    return api(`${taskPath()}/images`, {
      method: "POST",
      body: JSON.stringify({ name: file.name, data })
    });
  }

  dom.imageInput.addEventListener("change", () => {
    const file = dom.imageInput.files?.[0];
    const picker = pendingImageNode;
    pendingImageNode = null;
    const found = picker?.task === current && mode === "flow" ? findNode(picker.nodeId) : null;
    if (!file || !found) return;
    perform(async () => {
      const result = await uploadAsset(file);
      assets = null;
      assignNodeAsset(found.node, picker.key, result.name);
      toast(`已导入 ${result.name}`);
      await loadTasks();
    });
  });

  dom.assetInput.addEventListener("change", () => {
    const files = Array.from(dom.assetInput.files);
    if (!files.length || mode !== "assets" || !current) return;
    perform(async () => {
      let uploaded = 0;
      const failures = [];
      for (const file of files) {
        try {
          await uploadAsset(file);
          uploaded++;
        } catch (error) {
          failures.push(error.message);
        }
      }
      await loadAssets();
      if (uploaded) await loadTasks();
      toast(failures.length ? `已上传 ${uploaded} 张，${failures.length} 张失败：${failures[0]}` : `已上传 ${uploaded} 张图片`, Boolean(failures.length));
    });
  });

  function fileToBase64(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result).split(",")[1] || "");
      reader.onerror = () => reject(new Error("读取图片失败"));
      reader.readAsDataURL(file);
    });
  }

  document.addEventListener("click", event => {
    if (busy) return;
    const target = event.target;
    const taskButton = target.closest("[data-task]");
    if (taskButton) return openTask(taskButton.dataset.task);

    const modeTab = target.closest(".tab[data-mode]");
    if (modeTab && current) {
      const requested = modeTab.dataset.mode;
      if (requested === "flow" && current.type !== "flow") return;
      mode = requested;
      selectedId = null;
      renderEditor();
      if (mode === "assets") perform(refreshAssetView);
      return;
    }
    const modeJump = target.closest("[data-mode-jump]");
    if (modeJump) {
      mode = modeJump.dataset.modeJump;
      renderEditor();
      return;
    }

    if (target.closest("#create-task") || target.closest("#empty-create")) return createTaskModal();
    if (target.closest("#open-tasks") || target.closest("#empty-tasks")) {
      dom.taskRail.classList.toggle("open");
      return;
    }
    if (target.closest("#save-task")) return saveCurrent();
    if (target.closest("#task-settings")) return taskSettingsModal();
    const assetTab = target.closest("[data-asset-view]");
    if (assetTab) {
      assetView = assetTab.dataset.assetView;
      renderAssets();
      return perform(refreshAssetView);
    }
    if (target.closest("[data-upload-assets]")) {
      dom.assetInput.value = "";
      return dom.assetInput.click();
    }
    if (target.closest("[data-reload-assets]")) return perform(refreshAssetView);
    const crop = target.closest("[data-crop-asset]");
    if (crop) return openCrop(crop.dataset.cropAsset);
    const restore = target.closest("[data-restore-asset]");
    if (restore) return restoreAsset(restore.dataset.restoreAsset);
    const historyPreview = target.closest("[data-preview-history]");
    if (historyPreview) {
      const entry = imageHistory?.find(item => item.id === historyPreview.dataset.previewHistory);
      if (entry) previewAsset(entry.name, entry.id);
      return;
    }
    const rename = target.closest("[data-rename-asset]");
    if (rename) return renameAssetModal(rename.dataset.renameAsset);
    const remove = target.closest("[data-delete-asset]");
    if (remove) return deleteAsset(remove.dataset.deleteAsset);
    if (target.closest("#preview-code")) return showCodePreview();
    const previewButton = target.closest("[data-preview-asset]");
    if (previewButton) return previewAsset(previewButton.dataset.previewAsset);
    if (target.closest("[data-close-modal]")) return closeModal();
    if (target === dom.modal) return closeModal();

    const categoryButton = target.closest("[data-category]");
    if (categoryButton) {
      category = categoryButton.dataset.category;
      if (!dom.modal.hidden && dom.modal.querySelector(".library-modal-grid")) {
        openLibrary(insertTarget);
      } else {
        renderFlowEditor();
      }
      return;
    }
    const addButton = target.closest("[data-add-def]");
    if (addButton) {
      if (target.closest("[data-library-drag]")) return;
      if (suppressAddClick) {
        suppressAddClick = false;
        return;
      }
      return addModule(Number(addButton.dataset.addDef));
    }
    const canvasButton = target.closest("[data-canvas-action]");
    if (canvasButton) return canvasAction(canvasButton.closest(".flow-canvas"), canvasButton.dataset.canvasAction);
    if (target.closest("[data-open-library]")) return openLibrary(insertTarget);
    if (target.matches(".flow-canvas") && selectedId) {
      selectedId = null;
      renderFlowEditor();
      return;
    }

    const drop = target.closest("[data-drop]");
    if (drop) {
      insertTarget = {
        parentId: drop.dataset.parent || null,
        slot: drop.dataset.slot,
        index: Number(drop.dataset.index)
      };
      return openLibrary(insertTarget);
    }
    const inlineAdd = target.closest("[data-inline-add]");
    if (inlineAdd) {
      const list = listAt(inlineAdd.dataset.parent, inlineAdd.dataset.slot);
      return openLibrary({
        parentId: inlineAdd.dataset.parent,
        slot: inlineAdd.dataset.slot,
        index: list?.length || 0
      });
    }

    const selectButton = target.closest("[data-select]");
    if (selectButton) {
      selectedId = selectButton.dataset.select;
      renderFlowEditor();
      return;
    }
    const foldButton = target.closest("[data-fold]");
    if (foldButton) {
      const id = foldButton.dataset.fold;
      expanded.has(id) ? expanded.delete(id) : expanded.add(id);
      renderFlowEditor();
      return;
    }
    const choice = target.closest("[data-choice-key]");
    if (choice) return updateNodeProperty(choice.dataset.choiceKey, choice.dataset.choiceValue);
    const selectAsset = target.closest("[data-select-asset]");
    if (selectAsset) return chooseTaskAsset(selectedId, selectAsset.dataset.selectAsset);
    const uploadImage = target.closest("[data-pick-image]");
    if (uploadImage) return chooseImage(selectedId, uploadImage.dataset.pickImage);
    const removeImage = target.closest("[data-remove-image]");
    if (removeImage) return updateNodeProperty(removeImage.dataset.removeImage, "");
    if (target.closest("[data-duplicate]")) return duplicateNode(selectedId);
    if (target.closest("[data-delete]")) {
      const found = findNode(selectedId);
      if (!found) return;
      found.list.splice(found.index, 1);
      selectedId = null;
      setDirty(true);
      renderFlowEditor();
      return;
    }
    const moveButton = target.closest("[data-move]");
    if (moveButton) {
      const found = findNode(selectedId);
      if (!found) return;
      const direction = Number(moveButton.dataset.move);
      const destination = found.index + direction;
      if (destination < 0 || destination >= found.list.length) return;
      const [node] = found.list.splice(found.index, 1);
      found.list.splice(destination, 0, node);
      setDirty(true);
      renderFlowEditor();
    }
  });

  document.addEventListener("input", event => {
    if (event.target === dom.search) renderTaskList();
  });

  document.addEventListener("change", event => {
    const found = findNode(selectedId);
    if (!found) return;
    if (event.target.matches("[data-title]")) {
      found.node.title = event.target.value.trim() || found.node.type;
      setDirty(true);
      renderFlowEditor();
    } else if (event.target.matches("[data-enabled]")) {
      found.node.enabled = event.target.checked;
      setDirty(true);
      renderFlowEditor();
    } else if (event.target.matches("[data-prop]")) {
      const key = event.target.dataset.prop;
      const value = event.target.dataset.number ? Number(event.target.value) : event.target.value;
      updateNodeProperty(key, value);
    }
  });

  document.addEventListener("pointerdown", event => {
    if (busy || drag || !event.isPrimary || event.button !== 0) return;
    const handle = event.target.closest("[data-drag]");
    if (handle) {
      event.preventDefault();
      const found = findNode(handle.dataset.drag);
      if (!found) return;
      drag = {
        kind: "node",
        id: handle.dataset.drag,
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        target: null,
        active: true
      };
      createDragGhost(nodeTitle(found.node), event);
      document.body.classList.add("dragging-module");
      handle.setPointerCapture?.(event.pointerId);
      return;
    }

    const libraryCard = event.target.closest("[data-add-def]");
    if (!libraryCard || libraryCard.closest(".library-modal-grid")) return;
    const libraryHandle = event.target.closest("[data-library-drag]");
    if (!libraryHandle && event.pointerType !== "mouse") return;
    drag = {
      kind: "library",
      defIndex: Number(libraryCard.dataset.addDef),
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      target: null,
      active: Boolean(libraryHandle)
    };
    (libraryHandle || libraryCard).setPointerCapture?.(event.pointerId);
    if (libraryHandle) {
      createDragGhost(catalogFlat[drag.defIndex]?.[1] || "添加模块", event);
      document.body.classList.add("dragging-module");
      event.preventDefault();
    }
  });

  document.addEventListener("pointermove", event => {
    if (drag?.pointerId !== event.pointerId) return;
    if (!drag.active) {
      if (Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) < 6) return;
      drag.active = true;
      createDragGhost(catalogFlat[drag.defIndex]?.[1] || "添加模块", event);
      document.body.classList.add("dragging-module");
    }
    event.preventDefault();
    drag.ghost.style.left = `${event.clientX + 12}px`;
    drag.ghost.style.top = `${event.clientY - 34}px`;
    drag.point = { x: event.clientX, y: event.clientY };
    updateModuleDrop(event.clientX, event.clientY);
    if (!drag.panFrame) drag.panFrame = requestAnimationFrame(panWhileDragging);
  });

  function updateModuleDrop(x, y) {
    clearDropHighlights();
    const pointTarget = document.elementFromPoint(x, y);
    const hit = pointTarget?.closest("[data-drop],[data-inline-add]");
    if (hit) {
      const parentId = hit.dataset.parent || null;
      const slot = hit.dataset.slot;
      drag.target = {
        parentId,
        slot,
        index: hit.hasAttribute("data-inline-add") ? listAt(parentId, slot)?.length || 0 : Number(hit.dataset.index)
      };
      hit.classList.add("drag-over");
      return;
    }

    drag.target = null;
    if (drag.kind !== "library" || !pointTarget) return;
    const flowList = pointTarget.closest("[data-flow-list]");
    const container = pointTarget.closest(
      '.flow-node[data-type="sequence"], .flow-node[data-type="repeat"], .flow-node[data-type="condition"]'
    );
    if (container && (!flowList || !container.contains(flowList))) {
      const parentId = container.dataset.node;
      const slot = container.dataset.type === "condition" ? "then" : "children";
      drag.target = {
        parentId,
        slot,
        index: listAt(parentId, slot)?.length || 0
      };
      container.classList.add("drag-over");
    } else if (flowList) {
      drag.target = {
        parentId: flowList.dataset.parent || null,
        slot: flowList.dataset.slot,
        index: Number(flowList.dataset.length)
      };
      flowList.classList.add("drag-over");
    }
  }

  function panWhileDragging() {
    if (!drag) return;
    drag.panFrame = null;
    const canvas = dom.content.querySelector(".flow-canvas");
    if (!canvas || !drag.point) return;
    const { x, y } = drag.point;
    const bounds = canvas.getBoundingClientRect();
    if (x < bounds.left || x > bounds.right || y < bounds.top || y > bounds.bottom) return;
    const edge = Math.min(48, bounds.width / 4, bounds.height / 4);
    if (edge <= 0) return;
    const speed = (fromStart, fromEnd) => Math.max(0, 1 - fromStart / edge) * 13 - Math.max(0, 1 - fromEnd / edge) * 13;
    const dx = speed(x - bounds.left, bounds.right - x);
    const dy = speed(y - bounds.top, bounds.bottom - y);
    if (!dx && !dy) return;
    moveCanvas(canvas, dx, dy);
    updateModuleDrop(x, y);
    drag.panFrame = requestAnimationFrame(panWhileDragging);
  }

  document.addEventListener("pointerup", event => {
    if (drag?.pointerId !== event.pointerId) return;
    const state = drag;
    drag = null;
    if (state.panFrame) cancelAnimationFrame(state.panFrame);
    state.ghost?.remove();
    document.body.classList.remove("dragging-module");
    clearDropHighlights();
    if (!state.active) return;
    if (state.kind === "library") {
      suppressAddClick = true;
      setTimeout(() => { suppressAddClick = false; }, 0);
    }
    if (!state.target && state.kind === "library") {
      const dropPoint = document.elementFromPoint(event.clientX, event.clientY);
      if (dropPoint?.closest(".flow-canvas") && !dropPoint.closest("[data-canvas-controls]")) {
        state.target = {
          parentId: null,
          slot: "children",
          index: flow?.nodes?.length || 0
        };
      }
    }
    if (!state.target) return;
    if (state.kind === "node") {
      if (moveNode(state.id, state.target)) renderFlowEditor();
    } else {
      addModule(state.defIndex, state.target);
    }
  });

  document.addEventListener("pointercancel", event => {
    if (drag?.pointerId !== event.pointerId) return;
    if (drag.panFrame) cancelAnimationFrame(drag.panFrame);
    drag.ghost?.remove();
    drag = null;
    document.body.classList.remove("dragging-module");
    clearDropHighlights();
  });

  function clearDropHighlights() {
    document.querySelectorAll(".drop-zone.drag-over, .inline-add.drag-over, .empty-flow.drag-over, .flow-node.drag-over, .flow-list.drag-over")
      .forEach(element => element.classList.remove("drag-over"));
  }

  function createDragGhost(label, event) {
    const ghost = document.createElement("div");
    ghost.className = "drag-ghost";
    ghost.textContent = label;
    ghost.style.left = `${event.clientX + 12}px`;
    ghost.style.top = `${event.clientY - 34}px`;
    document.body.appendChild(ghost);
    drag.ghost = ghost;
  }

  window.addEventListener("beforeunload", event => {
    if (!dirtyModes.size) return;
    event.preventDefault();
    event.returnValue = "";
  });

  loadTasks();
})();
