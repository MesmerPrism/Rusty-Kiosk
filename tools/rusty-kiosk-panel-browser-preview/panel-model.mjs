const baseEntries = [
  {
    key: "package:com.example.browser",
    label: "Orbit Browser",
    packageName: "com.example.browser",
    installed: true,
    launchable: true,
    tags: ["onboarding", "web"],
    source: "android-launcher",
  },
  {
    key: "package:io.example.movement",
    label: "Movement Demo",
    packageName: "io.example.movement",
    installed: true,
    launchable: true,
    tags: ["demo", "movement"],
    source: "quest-vr",
  },
  {
    key: "package:com.example.gallery",
    label: "System Gallery",
    packageName: "com.example.gallery",
    installed: true,
    launchable: false,
    tags: ["utilities"],
    source: "tag-file-installed-package",
  },
  {
    key: "missing-name:training library",
    label: "Training Library",
    packageName: null,
    installed: false,
    launchable: false,
    tags: ["onboarding"],
    source: "tag-file",
  },
];

export const scenarioNames = ["catalog-ready", "tag-filter-missing", "guard-setup"];

export function scenarioState(name = "catalog-ready") {
  const scenario = scenarioNames.includes(name) ? name : scenarioNames[0];
  const state = {
    schema: "rusty.kiosk.browser_preview_state.v1",
    scenario,
    entries: clone(baseEntries),
    searchQuery: "",
    selectedTag: null,
    selectedKey: "package:com.example.browser",
    statusLine: "3 installed · 1 not installed · synthetic preview",
    tagFilePath:
      "/sdcard/Android/data/io.github.mesmerprism.rustykiosk/files/tags/app-tags.v1.json",
    guardEnabled: true,
    userControlsOpen: false,
    userControls: {
      setupHelperInstalled: true,
      setupHelperReady: true,
      requestWifiAfterBoot: true,
      wirelessDebuggingEnabled: true,
      accessibilityEnabled: true,
      operationInProgress: null,
      message: "Dedicated setup helper is installed and provisioned.",
    },
  };
  if (scenario === "tag-filter-missing") {
    state.selectedTag = "onboarding";
    state.selectedKey = "missing-name:training library";
  }
  if (scenario === "guard-setup") {
    state.selectedKey = "package:io.example.movement";
    state.guardEnabled = false;
    state.userControlsOpen = true;
    state.userControls.accessibilityEnabled = false;
    state.userControls.message =
      "Wi-Fi ADB is ready. Accessibility remains off until you explicitly enable it.";
  }
  return state;
}

export function deriveTags(state) {
  return [...new Set(state.entries.flatMap((entry) => entry.tags.map(normalizeTag)))]
    .filter(Boolean)
    .sort((left, right) => left.localeCompare(right));
}

export function visibleEntries(state) {
  const query = normalize(state.searchQuery);
  const selectedTag = state.selectedTag ? normalizeTag(state.selectedTag) : null;
  return state.entries
    .filter((entry) => !selectedTag || entry.tags.map(normalizeTag).includes(selectedTag))
    .filter(
      (entry) =>
        !query ||
        normalize(entry.label).includes(query) ||
        normalize(entry.packageName ?? "").includes(query) ||
        entry.tags.some((tag) => normalize(tag).includes(query)),
    )
    .sort(
      (left, right) =>
        Number(right.installed) - Number(left.installed) ||
        left.label.localeCompare(right.label, undefined, { sensitivity: "base" }) ||
        (left.packageName ?? "").localeCompare(right.packageName ?? ""),
    );
}

export function selectedEntry(state) {
  return state.entries.find((entry) => entry.key === state.selectedKey) ?? null;
}

export function addTag(state, value) {
  const tag = normalizeTag(value);
  if (!tag) return state;
  return updateSelected(state, (entry) => ({
    ...entry,
    tags: [...new Set([...entry.tags.map(normalizeTag), tag])].sort(),
  }));
}

export function removeTag(state, value) {
  const tag = normalizeTag(value);
  return updateSelected(state, (entry) => ({
    ...entry,
    tags: entry.tags.filter((candidate) => normalizeTag(candidate) !== tag),
  }));
}

export function importPreviewState(value) {
  if (!value || value.schema !== "rusty.kiosk.browser_preview_state.v1") {
    throw new Error("Unsupported Rusty Kiosk preview state.");
  }
  const base = scenarioState(value.scenario);
  if (!Array.isArray(value.entries) || value.entries.length > 100) {
    throw new Error("Preview entries are invalid.");
  }
  return {
    ...base,
    entries: value.entries.map((entry) => ({
      key: String(entry.key).slice(0, 160),
      label: String(entry.label).slice(0, 120),
      packageName: entry.packageName == null ? null : String(entry.packageName).slice(0, 180),
      installed: Boolean(entry.installed),
      launchable: Boolean(entry.launchable),
      tags: Array.isArray(entry.tags)
        ? [...new Set(entry.tags.map(normalizeTag).filter(Boolean))].slice(0, 24)
        : [],
      source: String(entry.source ?? "imported").slice(0, 80),
    })),
    searchQuery: String(value.searchQuery ?? "").slice(0, 120),
    selectedTag: value.selectedTag == null ? null : normalizeTag(String(value.selectedTag)),
    selectedKey: value.selectedKey == null ? null : String(value.selectedKey).slice(0, 160),
    statusLine: String(value.statusLine ?? base.statusLine).slice(0, 180),
    guardEnabled: Boolean(value.guardEnabled),
    userControlsOpen: Boolean(value.userControlsOpen),
    userControls: {
      ...base.userControls,
      setupHelperInstalled: Boolean(value.userControls?.setupHelperInstalled),
      setupHelperReady: Boolean(value.userControls?.setupHelperReady),
      requestWifiAfterBoot: Boolean(value.userControls?.requestWifiAfterBoot),
      wirelessDebuggingEnabled: Boolean(value.userControls?.wirelessDebuggingEnabled),
      accessibilityEnabled: Boolean(value.userControls?.accessibilityEnabled),
      operationInProgress:
        value.userControls?.operationInProgress == null
          ? null
          : String(value.userControls.operationInProgress).slice(0, 40),
      message: String(value.userControls?.message ?? base.userControls.message).slice(0, 240),
    },
  };
}

function updateSelected(state, update) {
  if (!state.selectedKey) return state;
  return {
    ...state,
    entries: state.entries.map((entry) => (entry.key === state.selectedKey ? update(entry) : entry)),
  };
}

function normalize(value) {
  return String(value).trim().replace(/\s+/g, " ").toLocaleLowerCase();
}

function normalizeTag(value) {
  return normalize(value).slice(0, 40);
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}
