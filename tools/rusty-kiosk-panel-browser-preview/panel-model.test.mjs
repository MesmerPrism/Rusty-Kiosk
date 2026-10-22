import assert from "node:assert/strict";
import {
  addTag,
  deriveTags,
  guardReady,
  importPreviewState,
  removeTag,
  selectKioskMode,
  scenarioState,
  selectedEntry,
  setCatalogSummarySharing,
  visibleEntries,
} from "./panel-model.mjs";

const ready = scenarioState("catalog-ready");
assert.equal(visibleEntries(ready).length, 4);
assert.equal(selectedEntry(ready).label, "Orbit Browser");
assert.equal(selectedEntry(ready).launchOptions.length, 2);
assert.equal(selectedEntry(ready).launchOptions[0].optionId, "playlist.preview-loop");
assert.deepEqual(deriveTags(ready), ["demo", "movement", "onboarding", "utilities", "web"]);

const missing = scenarioState("tag-filter-missing");
assert.deepEqual(
  visibleEntries(missing).map((entry) => entry.label),
  ["Orbit Browser", "Training Library"],
);
assert.equal(selectedEntry(missing).installed, false);

const searched = { ...ready, searchQuery: "io.example" };
assert.deepEqual(visibleEntries(searched).map((entry) => entry.label), ["Movement Demo"]);
const multiTerm = { ...ready, searchQuery: "orbit onboarding" };
assert.deepEqual(visibleEntries(multiTerm).map((entry) => entry.label), ["Orbit Browser"]);
const splitFields = { ...ready, searchQuery: "example web" };
assert.deepEqual(visibleEntries(splitFields).map((entry) => entry.label), ["Orbit Browser"]);
const separatedTerms = { ...ready, searchQuery: "orbit/onboarding" };
assert.deepEqual(visibleEntries(separatedTerms).map((entry) => entry.label), ["Orbit Browser"]);
const quotedLabel = { ...ready, searchQuery: '"orbit browser"' };
assert.deepEqual(visibleEntries(quotedLabel).map((entry) => entry.label), ["Orbit Browser"]);
const quotedPackage = { ...ready, searchQuery: '"example/browser"' };
assert.deepEqual(visibleEntries(quotedPackage).map((entry) => entry.label), ["Orbit Browser"]);
assert.deepEqual(visibleEntries({ ...ready, searchQuery: '"example web"' }), []);
assert.deepEqual(visibleEntries({ ...ready, searchQuery: "movement web" }), []);
assert.deepEqual(visibleEntries({ ...ready, searchQuery: "movement/web" }), []);

const tagged = addTag(ready, "  Utilities  ");
assert.deepEqual(selectedEntry(tagged).tags, ["onboarding", "utilities", "web"]);
assert.deepEqual(selectedEntry(removeTag(tagged, "web")).tags, ["onboarding", "utilities"]);

assert.throws(() => importPreviewState({ schema: "wrong" }), /Unsupported/);
assert.equal(importPreviewState(tagged).guardEnabled, true);
assert.equal(importPreviewState(tagged).entries[0].launchOptions.length, 2);
assert.equal(scenarioState("guard-setup").userControlsOpen, true);
assert.equal(scenarioState("guard-setup").userControls.accessibilityEnabled, false);
assert.equal(ready.userControls.passthroughStyle, "natural");
assert.equal(ready.userControls.systemPassthroughEnabled, true);

assert.equal(selectKioskMode(ready, "visibility").guardEnabled, true);
assert.equal(selectKioskMode({ ...ready, userControls: { ...ready.userControls,
  visibilityReady: false } }, "visibility").guardEnabled, false);
assert.equal(selectKioskMode(ready, "shell").guardEnabled, false);
assert.equal(scenarioState("shell-ready").guardEnabled, true);
assert.equal(scenarioState("shell-unavailable").guardEnabled, false);
assert.equal(scenarioState("shell-original-held").guardEnabled, false);
for (const kioskMode of ["shell", "accessibility", "visibility"]) {
  assert.equal(guardReady({ ...ready.userControls, kioskMode, shellCompanionReady: true, shellCustodyHeld: true }), false);
}
assert.equal(guardReady({ ...ready.userControls, kioskMode: "unavailable" }), false);
assert.equal(importPreviewState({ ...ready, userControls: { ...ready.userControls, kioskMode: "future" } }).userControls.kioskMode, "unavailable");
console.log("Rusty Kiosk browser panel model passed.");

assert.equal(setCatalogSummarySharing(ready, true).userControls.catalogSummaryGranted, false);
const linked = { ...ready, userControls: { ...ready.userControls, operatorBridgeEnabled: true } };
const shared = setCatalogSummarySharing(linked, true);
assert.equal(shared.userControls.catalogSummaryGranted, true);
assert.equal(setCatalogSummarySharing(shared, false).userControls.catalogSummaryGranted, false);
assert.equal(importPreviewState(shared).userControls.catalogSummaryGranted, true);
assert.equal(importPreviewState({ ...shared, userControls: { ...shared.userControls, operatorBridgeEnabled: false } }).userControls.catalogSummaryGranted, false);

const blockedLink = { ...linked, userControls: { ...linked.userControls, operatorBridgePersistenceBlocked: true } };
assert.equal(setCatalogSummarySharing(blockedLink, true).userControls.catalogSummaryGranted, false);
