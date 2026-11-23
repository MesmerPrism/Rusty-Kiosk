import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { runInNewContext } from "node:vm";

const browser = new URL("../", import.meta.url);
const read = (path, encoding) => readFile(new URL(path, browser), encoding);
const scope = "https://fixture.example/quest-audio/";
const asset = (path) => new URL(path, scope).href;

test("the adapted pairing client and retained license are pinned", async () => {
  const pin = JSON.parse(await read("vendor/quest-pairing.source.json", "utf8"));
  assert.equal(pin.commit, "5ecdbeefb53b017b7044d81d5ead1b3054d42272");
  assert.equal(pin.source, "quest-pairing.js");
  assert.equal(pin.author, "George Fejer");
  assert.deepEqual(Object.keys(pin.sha256).sort(), ["quest-pairing.LICENSE", "quest-pairing.js"]);
  for (const [path, hash] of Object.entries(pin.sha256)) {
    assert.equal(createHash("sha256").update(await read(`vendor/${path}`)).digest("hex"), hash);
  }
});

test("manifest is path-relative and install icons match their declared size", async () => {
  const manifest = JSON.parse(await read("manifest.webmanifest", "utf8"));
  assert.equal(manifest.scope, "./");
  assert.equal(manifest.start_url, "./");
  assert.equal(manifest.display, "standalone");
  for (const size of [192, 512]) {
    const icon = manifest.icons.find((entry) => entry.sizes === `${size}x${size}`);
    assert.ok(icon);
    const file = await read(icon.src);
    assert.equal(file.toString("hex", 0, 8), "89504e470d0a1a0a");
    assert.equal(file.readUInt32BE(16), size);
    assert.equal(file.readUInt32BE(20), size);
  }
});

test("service worker installs the static shell and ignores queries, foreign assets and writes", async () => {
  const handlers = new Map();
  const installed = new Set();
  const deleted = [];
  const cachePrefix = `quest-status-audio:${scope}:`;
  const cache = {
    async addAll(requests) { for (const request of requests) installed.add(request.url); },
    async match(url) { return installed.has(url) ? `cached ${url}` : null; },
  };
  const context = {
    self: { registration: { scope }, addEventListener: (name, handler) => handlers.set(name, handler) },
    caches: {
      async open() { return cache; },
      async keys() { return [`${cachePrefix}old`, `${cachePrefix}3`, `${cachePrefix}4`, "a-different-app"]; },
      async delete(name) { deleted.push(name); },
    },
    fetch() { throw new Error("Unexpected network fallback in fixture"); },
    Request,
    URL,
  };
  const worker = await read("sw.js", "utf8");
  runInNewContext(worker, context);
  let lifetime;
  handlers.get("install")({ waitUntil: (promise) => { lifetime = promise; } });
  await lifetime;
  assert.equal(installed.size, 12);
  for (const url of installed) {
    assert.ok(url.startsWith(scope));
    assert.ok((await read(new URL(url).pathname.slice(new URL(scope).pathname.length))).length > 0);
  }

  function request(url, method = "GET", mode = "same-origin") {
    let response;
    handlers.get("fetch")({ request: { url, method, mode }, respondWith: (value) => { response = value; } });
    return response;
  }
  assert.equal(await request(scope, "GET", "navigate"), `cached ${asset("index.html")}`);
  assert.equal(await request(asset("app.js")), `cached ${asset("app.js")}`);
  assert.equal(await request(asset("page-qr.js")), `cached ${asset("page-qr.js")}`);
  assert.equal(await request(asset("quest-link.js")), `cached ${asset("quest-link.js")}`);
  assert.equal(await request(asset("vendor/quest-pairing.js")), `cached ${asset("vendor/quest-pairing.js")}`);
  assert.equal(await request(asset("vendor/qrcode-generator.mjs")), `cached ${asset("vendor/qrcode-generator.mjs")}`);
  assert.equal(request(asset("app.js") + "?token=fixture"), undefined);
  assert.equal(request(asset("app.js"), "POST"), undefined);
  assert.equal(request(asset("test/fake-quest.js")), undefined);
  assert.equal(request("https://other.example/quest-audio/app.js"), undefined);
  assert.equal(request("http://192.168.7.20:47876/v1/gateway"), undefined);

  handlers.get("activate")({ waitUntil: (promise) => { lifetime = promise; } });
  await lifetime;
  assert.deepEqual(deleted, [`${cachePrefix}old`, `${cachePrefix}3`]);
});
