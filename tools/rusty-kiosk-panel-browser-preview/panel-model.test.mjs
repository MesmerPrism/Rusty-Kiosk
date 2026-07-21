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
assert.deepEqual(deriveTags(ready), ["demo", "movement", "onboarding", "utilities", "web"]);

const missing = scenarioState("tag-filter-missing");
assert.deepEqual(
  visibleEntries(missing).map((entry) => entry.label),
  ["Orbit Browser", "Training Library"],
);
assert.equal(selectedEntry(missing).installed, false);

const searched = { ...ready, searchQuery: "io.example" };
assert.deepEqual(visibleEntries(searched).map((entry) => entry.label), ["Movement Demo"]);

const tagged = addTag(ready, "  Utilities  ");
assert.deepEqual(selectedEntry(tagged).tags, ["onboarding", "utilities", "web"]);
assert.deepEqual(selectedEntry(removeTag(tagged, "web")).tags, ["onboarding", "utilities"]);

assert.throws(() => importPreviewState({ schema: "wrong" }), /Unsupported/);
assert.equal(importPreviewState(tagged).guardEnabled, true);
assert.equal(scenarioState("guard-setup").userControlsOpen, true);
assert.equal(scenarioState("guard-setup").userControls.accessibilityEnabled, false);

console.log("Rusty Kiosk browser panel model passed.");
