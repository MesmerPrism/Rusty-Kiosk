// Bump VERSION whenever the static shell changes. A waiting worker keeps its own cache.
const VERSION = "1";
const SCOPE = self.registration.scope;
const PREFIX = `quest-status-audio:${SCOPE}:`;
const CACHE = PREFIX + VERSION;
const INDEX = new URL("index.html", SCOPE).href;
const ASSETS = new Set([
  "index.html", "styles.css", "app.js", "controller.js", "protocol.js",
  "vendor/gatt-lifetime.js", "manifest.webmanifest", "icons/quest-audio-192.png", "icons/quest-audio-512.png",
].map((asset) => new URL(asset, SCOPE).href));

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(
    [...ASSETS].map((url) => new Request(url, { cache: "reload" })),
  )));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(
    keys.filter((key) => key.startsWith(PREFIX) && key !== CACHE).map((key) => caches.delete(key)),
  )));
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const asset = request.mode === "navigate" && request.url === SCOPE ? INDEX : request.url;
  if (!ASSETS.has(asset)) return;
  event.respondWith(caches.open(CACHE).then(async (cache) => (await cache.match(asset)) ?? fetch(request)));
});
