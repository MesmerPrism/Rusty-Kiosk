import qrcode from './vendor/qrcode-generator.mjs';

const QUIET_MODULES = 4;

export function shareablePageUrl(href) {
  let url;
  try {
    url = new URL(href);
  } catch {
    return null;
  }
  const host = url.hostname.replace(/\.$/, '');
  if (url.protocol !== 'https:' || url.username || url.password ||
      host === 'localhost' || host.endsWith('.localhost') || host.startsWith('127.') ||
      host === '0.0.0.0' || host === '[::]' || host === '[::1]' || host.startsWith('[::ffff:7f')) {
    return null;
  }
  url.search = '';
  url.hash = '';
  return url.href.length <= 512 ? url.href : null;
}

export function renderPageQr(canvas, href) {
  const link = shareablePageUrl(href);
  const context = link && canvas?.getContext('2d');
  if (!context) return null;

  const code = qrcode(0, 'M');
  code.addData(link, 'Byte');
  code.make();
  const modules = code.getModuleCount();
  const total = modules + 2 * QUIET_MODULES;
  const scale = Math.max(3, Math.floor(240 / total));
  canvas.width = total * scale;
  canvas.height = canvas.width;
  context.fillStyle = '#fff';
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = '#000';
  for (let row = 0; row < modules; row++) {
    for (let column = 0; column < modules; column++) {
      if (code.isDark(row, column)) {
        context.fillRect((column + QUIET_MODULES) * scale, (row + QUIET_MODULES) * scale, scale, scale);
      }
    }
  }
  return link;
}
