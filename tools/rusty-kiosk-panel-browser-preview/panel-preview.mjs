import {
  addTag,
  deriveTags,
  importPreviewState,
  removeTag,
  scenarioNames,
  scenarioState,
  selectedEntry,
  visibleEntries,
} from "./panel-model.mjs";

const contractUrl = "../../references/rusty-kiosk-panel-contract.v1.json";
const nativePreviewRoot = "../../artifacts/rusty-kiosk-native-panel-preview";
const params = new URLSearchParams(location.search);
const elements = {
  toolbar: document.querySelector("#authoring-toolbar"),
  viewport: document.querySelector("#preview-viewport"),
  frame: document.querySelector("#preview-frame"),
  scaleShell: document.querySelector("#preview-scale-shell"),
  panel: document.querySelector("#rusty-kiosk-panel"),
  header: document.querySelector("#panel-header"),
  search: document.querySelector("#rusty-kiosk-search"),
  tags: document.querySelector("#rusty-kiosk-tag-filters"),
  list: document.querySelector("#rusty-kiosk-app-list"),
  details: document.querySelector("#rusty-kiosk-app-details"),
  footer: document.querySelector("#panel-footer"),
  scenario: document.querySelector("#preview-scenario"),
  renderer: document.querySelector("#preview-renderer"),
  guard: document.querySelector("#preview-guard"),
  exportState: document.querySelector("#export-state"),
  importState: document.querySelector("#import-state"),
  importFile: document.querySelector("#import-state-file"),
  nativeLayer: document.querySelector("#native-panel-layer"),
  nativeImage: document.querySelector("#native-panel-image"),
  nativeMessage: document.querySelector("#native-panel-message"),
  nativeStatus: document.querySelector("#native-preview-status"),
};

const contract = await loadContract();
let state = scenarioState(params.get("scenario") ?? "catalog-ready");
let nativeManifest = null;

for (const name of scenarioNames) {
  const option = document.createElement("option");
  option.value = name;
  option.textContent = scenarioLabel(name);
  elements.scenario.append(option);
}

elements.renderer.value = params.get("view") ?? "browser";
if (!["browser", "native", "compare"].includes(elements.renderer.value)) {
  elements.renderer.value = "browser";
}
if (params.get("capture") === "1") document.body.classList.add("capture-mode");

elements.scenario.addEventListener("change", () => {
  state = scenarioState(elements.scenario.value);
  render();
});
elements.renderer.addEventListener("change", () => {
  if (elements.renderer.value === "compare") {
    state = scenarioState(state.scenario);
    render();
    return;
  }
  updateRendererView();
});
elements.guard.addEventListener("change", () => {
  state = { ...state, guardEnabled: elements.guard.checked };
  render();
});
elements.search.addEventListener("input", () => {
  state = { ...state, searchQuery: elements.search.value };
  renderCatalogue();
});
elements.exportState.addEventListener("click", exportState);
elements.importState.addEventListener("click", () => elements.importFile.click());
elements.importFile.addEventListener("change", importState);

new ResizeObserver(resizePreview).observe(elements.viewport);
window.addEventListener("resize", resizePreview);

render();
loadNativeManifest().then((manifest) => {
  nativeManifest = manifest;
  updateRendererView();
});

function render() {
  elements.scenario.value = state.scenario;
  elements.guard.checked = state.guardEnabled;
  elements.search.value = state.searchQuery;
  elements.header.replaceChildren(
    node("div", { className: "panel-title" }, [
      node("h1", {}, ["Rusty Kiosk"]),
      node("p", {}, [state.statusLine]),
    ]),
    button("Reload", "outline-button", () => {
      state = { ...state, statusLine: "Catalogue reloaded · synthetic preview" };
      render();
    }),
  );
  renderCatalogue();
  elements.footer.textContent = state.tagFilePath;
  updateRendererView();
  resizePreview();
}

function renderCatalogue() {
  renderTags();
  renderList();
  renderDetails();
  updateRendererView();
}

function renderTags() {
  const tags = deriveTags(state);
  elements.tags.replaceChildren(
    tagButton("All apps", state.selectedTag === null, () => {
      state = { ...state, selectedTag: null };
      renderCatalogue();
    }),
    ...tags.map((tag) =>
      tagButton(tag, state.selectedTag === tag, () => {
        state = { ...state, selectedTag: tag };
        renderCatalogue();
      }),
    ),
  );
}

function renderList() {
  const entries = visibleEntries(state);
  const rows = node("div", { className: "app-list-rows" });
  if (entries.length === 0) {
    rows.append(node("p", { className: "empty-copy", style: "padding: 12px" }, ["No apps match the current search and tag filter."]));
  } else {
    for (const entry of entries) rows.append(appRow(entry));
  }
  elements.list.replaceChildren(
    node("h2", { className: "section-title" }, [`Apps (${entries.length})`]),
    rows,
  );
}

function renderDetails() {
  const entry = selectedEntry(state);
  if (!entry) {
    elements.details.replaceChildren(node("p", { className: "empty-copy" }, ["Select an app"]));
    return;
  }

  const tagInput = node("input", { type: "text", autocomplete: "off" });
  const addButton = button("Add", "solid-button", () => {
    state = addTag(state, tagInput.value);
    tagInput.value = "";
    renderCatalogue();
  });
  addButton.disabled = true;
  tagInput.addEventListener("input", () => (addButton.disabled = !tagInput.value.trim()));
  const tagForm = node("div", { className: "tag-form" }, [
    node("label", { className: "field-shell" }, [node("span", {}, ["Add tag"]), tagInput]),
    addButton,
  ]);

  const tagRow = node(
    "div",
    { className: "detail-tags" },
    entry.tags.map((tag) =>
      button(`${tag} ×`, "tag-button", () => {
        state = removeTag(state, tag);
        renderCatalogue();
      }),
    ),
  );

  const normalLaunch = button("Normal launch", "solid-button secondary-button launch-button", () => {
    state = { ...state, statusLine: `Launched ${entry.label} normally · browser simulation` };
    render();
  });
  normalLaunch.id = "rusty-kiosk-normal-launch";
  normalLaunch.disabled = !entry.launchable;

  const kioskLaunch = button("Kiosk launch", "solid-button launch-button", () => {
    state = { ...state, statusLine: `Kiosk launched ${entry.label} · browser simulation` };
    render();
  });
  kioskLaunch.id = "rusty-kiosk-kiosk-launch";
  kioskLaunch.disabled = !entry.launchable || !state.guardEnabled;

  const children = [
    node("h2", { className: "detail-title" }, [entry.label]),
    node("p", { className: "detail-meta" }, [entry.packageName ?? "No package supplied"]),
    node("p", { className: `detail-status ${entry.installed ? "installed" : "missing"}` }, [statusLabel(entry)]),
    tagForm,
    tagRow,
    node("div", { className: "detail-divider" }),
    normalLaunch,
    kioskLaunch,
  ];

  if (state.guardEnabled) {
    children.push(node("p", { className: "detail-copy" }, ["Soft guard ready. Home #1 and #2 restore the app; Home #3 within five seconds returns here."]));
  } else {
    children.push(node("p", { className: "detail-copy missing" }, ["Kiosk launch needs the opt-in Accessibility service."]));
    const settings = button("Open Accessibility settings", "outline-button launch-button", () => {
      state = { ...state, statusLine: "Accessibility settings requested · browser simulation" };
      render();
    });
    settings.id = "rusty-kiosk-accessibility-settings";
    children.push(settings);
  }
  children.push(node("p", { className: "detail-copy" }, ["The guard is inactive in Rusty Kiosk. Press Home here to open Meta Home normally."]));
  elements.details.replaceChildren(...children);
}

function appRow(entry) {
  const row = node("button", { className: "app-row", type: "button" }, [
    node("span", { className: "app-row-heading" }, [
      node("strong", {}, [entry.label]),
      node("span", { className: entry.installed ? "installed" : "missing" }, [entry.installed ? "Installed" : "Not installed"]),
    ]),
    node("span", { className: "app-row-meta" }, [entry.packageName ?? "Name-only tag-file entry"]),
    node("span", { className: "app-row-tags" }, [entry.tags.join(" · ")]),
  ]);
  row.setAttribute("aria-selected", String(entry.key === state.selectedKey));
  row.addEventListener("click", () => {
    state = { ...state, selectedKey: entry.key };
    renderCatalogue();
  });
  return row;
}

function updateRendererView() {
  const mode = elements.renderer.value;
  elements.scaleShell.classList.toggle("native-mode", mode === "native");
  elements.scaleShell.classList.toggle("compare-mode", mode === "compare");
  elements.nativeLayer.hidden = mode === "browser";
  if (mode === "browser") {
    elements.nativeStatus.textContent = "Interactive synthetic projection";
    return;
  }
  const artifact = nativeManifest?.artifacts?.find((item) => item.scenario === state.scenario);
  if (!artifact) {
    elements.nativeImage.removeAttribute("src");
    elements.nativeMessage.textContent = "Generate the native preview to use this view.";
    elements.nativeStatus.textContent = "Native Android render unavailable";
    return;
  }
  elements.nativeMessage.textContent = "";
  elements.nativeImage.src = `${nativePreviewRoot}/${artifact.file}?sha=${artifact.sha256}`;
  const sourceState = nativeManifest.source_worktree_dirty ? "working copy" : "commit-bound";
  const isCanonicalScenario = JSON.stringify(state) === JSON.stringify(scenarioState(state.scenario));
  elements.nativeStatus.textContent =
    mode === "native"
      ? `Production Compose · 1953×1323 @ 288 dpi · ${sourceState}`
      : isCanonicalScenario
        ? `Aligned 50% comparison · ${sourceState}`
        : `Browser state changed · select this view again to realign · ${sourceState}`;
}

async function loadContract() {
  const response = await fetch(contractUrl, { cache: "no-store" });
  if (!response.ok) throw new Error(`Could not load panel contract: ${response.status}`);
  const value = await response.json();
  if (
    value.schema !== "rusty.kiosk.panel_contract.v1" ||
    value.surface.width_dp !== 1085 ||
    value.surface.height_dp !== 735 ||
    value.surface.layout_dpi !== 288
  ) {
    throw new Error("Rusty Kiosk panel contract does not match the preview renderer.");
  }
  return value;
}

async function loadNativeManifest() {
  try {
    const response = await fetch(`${nativePreviewRoot}/manifest.json`, { cache: "no-store" });
    if (!response.ok) return null;
    const value = await response.json();
    if (
      value.schema !== "rusty.kiosk.native_panel_preview_manifest.v1" ||
      value.native_raster_width_px !== contract.surface.native_raster_width_px ||
      value.native_raster_height_px !== contract.surface.native_raster_height_px
    ) {
      return null;
    }
    return value;
  } catch {
    return null;
  }
}

function resizePreview() {
  const viewportInset = document.body.classList.contains("capture-mode") ? 0 : 48;
  const width = elements.viewport.clientWidth - viewportInset;
  const height = elements.viewport.clientHeight - viewportInset;
  const scale = Math.min(1, width / contract.surface.width_dp, height / contract.surface.height_dp);
  const safeScale = Number.isFinite(scale) && scale > 0 ? scale : 1;
  document.documentElement.style.setProperty("--preview-scale", String(safeScale));
  elements.frame.style.width = `${Math.round(contract.surface.width_dp * safeScale)}px`;
  elements.frame.style.height = `${Math.round(contract.surface.height_dp * safeScale)}px`;
}

function exportState() {
  const blob = new Blob([JSON.stringify(state, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `rusty-kiosk-${state.scenario}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

async function importState() {
  const file = elements.importFile.files?.[0];
  if (!file) return;
  try {
    state = importPreviewState(JSON.parse(await file.text()));
    render();
  } catch (error) {
    elements.nativeStatus.textContent = error instanceof Error ? error.message : String(error);
  } finally {
    elements.importFile.value = "";
  }
}

function tagButton(label, selected, onClick) {
  const result = button(label, "tag-button", onClick);
  result.setAttribute("aria-pressed", String(selected));
  return result;
}

function button(label, className, onClick) {
  const result = node("button", { className, type: "button" }, [label]);
  result.addEventListener("click", onClick);
  return result;
}

function node(tagName, attributes = {}, children = []) {
  const result = document.createElement(tagName);
  for (const [name, value] of Object.entries(attributes)) {
    if (name === "className") result.className = value;
    else if (name === "style") result.setAttribute("style", value);
    else result.setAttribute(name, value);
  }
  for (const child of children) result.append(child);
  return result;
}

function statusLabel(entry) {
  if (!entry.installed) return "Not installed";
  if (!entry.launchable) return "Installed, no public launch activity";
  return "Installed";
}

function scenarioLabel(value) {
  return value
    .split("-")
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join(" ");
}
