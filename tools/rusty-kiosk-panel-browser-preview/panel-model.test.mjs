import assert from "node:assert/strict";
import {
  addTag,
  deriveTags,
  importPreviewState,
  removeTag,
  scenarioState,
  selectedEntry,
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

console.log("Rusty Kiosk browser panel model passed.");
