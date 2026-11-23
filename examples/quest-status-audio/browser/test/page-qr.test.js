import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { renderPageQr, shareablePageUrl } from '../page-qr.js';

test('facilitator links use HTTPS and exclude credentials, queries, fragments and loopback', () => {
  assert.equal(shareablePageUrl('https://examples.test/quest-audio/?code=secret#pair'),
    'https://examples.test/quest-audio/');
  for (const value of [
    'http://examples.test/quest-audio/', 'https://user:pass@examples.test/',
    'https://localhost:8765/', 'https://example.localhost/', 'https://127.1/',
    'https://[::1]/', 'https://0.0.0.0/', 'file:///example/index.html', 'invalid',
  ]) assert.equal(shareablePageUrl(value), null, value);
});

test('QR rendering starts with a white quiet zone and draws only the closed link', () => {
  function draw(href) {
    const pixels = [];
    const context = { fillRect(...rect) { pixels.push([this.fillStyle, ...rect]); } };
    const canvas = { getContext: () => context };
    const url = renderPageQr(canvas, href);
    return { url, width: canvas.width, height: canvas.height, pixels };
  }
  const rendered = draw('https://examples.test/quest-audio/?token=x#secret');
  assert.deepEqual(rendered, draw('https://examples.test/quest-audio/'));
  assert.equal(rendered.width, rendered.height);
  assert.deepEqual(rendered.pixels[0], ['#fff', 0, 0, rendered.width, rendered.height]);
  assert.ok(rendered.pixels.length > 100);
  for (const [color, x, y, width, height] of rendered.pixels.slice(1)) {
    assert.equal(color, '#000');
    assert.ok(x >= 4 * width && y >= 4 * height);
    assert.ok(x + 5 * width <= rendered.width && y + 5 * height <= rendered.height);
  }
  assert.equal(draw('http://127.0.0.1/').pixels.length, 0);
});

test('the QR generator and MIT license are pinned to unmodified supplier bytes', async () => {
  const directory = new URL('../vendor/', import.meta.url);
  const pin = JSON.parse(await readFile(new URL('qrcode-generator.source.json', directory), 'utf8'));
  assert.equal(pin.commit, '83b7e8fe3fddd3b0368dbafd6ce56995bd25e3c8');
  for (const [file, expected] of Object.entries(pin.sha256)) {
    const bytes = await readFile(new URL(file, directory));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), expected);
  }
});
