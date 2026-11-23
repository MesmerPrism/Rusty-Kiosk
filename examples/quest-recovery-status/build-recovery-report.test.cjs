const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { projectRecovery } = require('./recovery-status.js');
const { sampleReport } = require('./sample.js');

const cli = path.join(__dirname, 'build-recovery-report.cjs');
const now = Date.parse('2026-01-01T12:00:00Z');
const otherBoot = '00000000-0000-4000-8000-000000000202';
const sources = ['kiosk', 'qfm', 'approval', 'shell', 'watchdog'];

function execute(manifest) {
  return spawnSync(process.execPath, [cli, manifest], { encoding: 'utf8' });
}

function tempFolder(t) {
  const tempRoot = fs.realpathSync.native(os.tmpdir());
  const dir = fs.mkdtempSync(path.join(tempRoot, 'quest-report-test-'));
  t.after(() => {
    assert.equal(fs.lstatSync(dir).isDirectory(), true);
    const resolved = fs.realpathSync.native(dir);
    assert.equal(path.dirname(resolved), tempRoot);
    assert.match(path.basename(resolved), /^quest-report-test-[a-z0-9]+$/i);
    assert.equal(resolved, dir);
    fs.rmSync(resolved, { recursive: true, force: true });
  });
  return dir;
}

function fixture(t, scenario = 'ready') {
  const dir = tempFolder(t);
  const manifestPath = path.join(dir, 'manifest.json');
  const report = sampleReport(scenario, now);
  const manifest = { schema: 'rusty-kiosk.recovery-manifest.v1', context: report.context };
  for (const key of sources) {
    if (!report[key]) continue;
    const { receipt, ...scope } = report[key];
    manifest[key] = { ...scope, file: `${key}.json` };
    if (key === 'shell') manifest[key].transport = 'wifi-adb';
  }
  function write() {
    fs.writeFileSync(manifestPath, JSON.stringify(manifest));
    for (const key of sources) {
      if (report[key]) fs.writeFileSync(path.join(dir, `${key}.json`), JSON.stringify(report[key].receipt));
    }
  }
  return { dir, manifestPath, report, manifest, write, run: () => execute(manifestPath) };
}

function successful(result) {
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stderr, '');
  return JSON.parse(result.stdout);
}

function rejected(result, expected) {
  assert.equal(result.status, 1);
  assert.equal(result.stdout, '');
  assert.match(result.stderr, expected);
  assert.doesNotMatch(result.stderr, /private-unprinted|quest-report-test-/);
}

test('builds compatible panel evidence and drops unrelated owner fields', (t) => {
  const f = fixture(t);
  for (const key of sources) f.report[key].receipt.message = `private-unprinted-${key}`;
  f.report.kiosk.receipt.state.selected_package = 'private-unprinted-package';
  f.report.qfm.receipt.kiosk.State.OperatorBridgeEndpoint = 'private-unprinted-endpoint';
  f.report.shell.receipt.endpoint = 'private-unprinted-transport';
  f.report.watchdog.receipt.owner_id = 'private-unprinted-owner';
  f.write();
  const result = f.run();
  const report = successful(result);
  assert.doesNotMatch(result.stdout, /private-unprinted|watchdog\.json/);
  assert.equal(report.schema, 'rusty-kiosk.recovery-status.v1');
  assert.equal(report.kiosk.receipt.state.last_boot_request.outcome, 'requested');
  assert.equal(report.shell.receipt.transport, 'wifi-adb');
  const labels = Object.values(projectRecovery(report, now)).map(card => card.label);
  assert.deepEqual(labels, ['Request sent', 'Wearer approved', 'Enabled', 'Authenticated shell', 'Watchdog active']);
});

test('retains source times and stale evidence without stamping it as current', (t) => {
  const f = fixture(t, 'wifi');
  f.manifest.kiosk.observed_at_ms = now - 120_001;
  f.report.kiosk.receipt.recorded_at_ms = now - 120_008;
  f.write();
  const report = successful(f.run());
  assert.equal(report.context.observed_at_ms, now);
  assert.equal(report.kiosk.observed_at_ms, now - 120_001);
  assert.equal(report.kiosk.receipt.recorded_at_ms, now - 120_008);
  assert.equal(projectRecovery(report, now).boot.label, 'Stale');
});

test('supports context-only reports and refuses unknown manifest keys', (t) => {
  const f = fixture(t);
  for (const key of sources) delete f.manifest[key];
  delete f.manifest.context.boot_count;
  f.write();
  const report = successful(f.run());
  assert.deepEqual(report, { schema: 'rusty-kiosk.recovery-status.v1', context: f.manifest.context });
  f.manifest.extra = 'private-unprinted-field';
  f.write();
  rejected(f.run(), /manifest/i);
});

test('retains Kiosk’s unknown boot count without qualifying it for this boot', (t) => {
  const f = fixture(t, 'wifi');
  f.report.kiosk.receipt.state.last_boot_request.boot_count = -1;
  f.write();
  const report = successful(f.run());
  assert.equal(report.kiosk.receipt.state.last_boot_request.boot_count, -1);
  assert.equal(projectRecovery(report, now).boot.label, 'Unknown');
});

test('rejects mismatched envelope serial and boot, and hardware endpoint aliases', (t) => {
  const f = fixture(t);
  const original = { ...f.manifest.qfm };
  for (const changed of [{ serial: 'other-headset' }, { boot_id: otherBoot }, { serial: '192.0.2.14:43112' }]) {
    f.manifest.qfm = { ...original, ...changed };
    f.write();
    rejected(f.run(), /qfm/i);
  }
  f.manifest.qfm = original;
  f.manifest.context.serial = 'adb-demo._adb-tls-connect._tcp';
  f.write();
  rejected(f.run(), /context/i);
});

test('rejects contradictions inside shell, watchdog and boot observations', (t) => {
  const f = fixture(t);
  const changes = [
    ['shell', 'serial', 'other-headset'], ['shell', 'boot', otherBoot], ['shell', 'transport', 'usb'],
    ['watchdog', 'boot_id', otherBoot], ['watchdog', 'serial', 'other-headset'],
  ];
  for (const [source, key, value] of changes) {
    const receipt = f.report[source].receipt;
    const previous = receipt[key];
    receipt[key] = value;
    f.write();
    rejected(f.run(), new RegExp(source, 'i'));
    if (previous === undefined) delete receipt[key];
    else receipt[key] = previous;
  }
  f.report.kiosk.receipt.state.last_boot_request.boot_count -= 1;
  f.write();
  rejected(f.run(), /kiosk/i);
});

test('requires shell transport evidence beyond a manifest declaration and verified UID', (t) => {
  const f = fixture(t);
  delete f.report.shell.receipt.transport;
  f.write();
  rejected(f.run(), /shell.*transport/i);
});

test('accepts verified legacy IP endpoint connection evidence and redacts it', (t) => {
  const f = fixture(t);
  const receipt = f.report.shell.receipt;
  delete receipt.transport;
  for (const [endpoint, verb] of [['192.0.2.14:43112', 'connected to'], ['[2001:db8::1]:43112', 'already connected to']]) {
    receipt.endpoint = endpoint;
    receipt.connection = `${verb} ${endpoint}`;
    f.write();
    const result = f.run();
    const report = successful(result);
    assert.equal(projectRecovery(report, now).shell.label, 'Authenticated shell');
    assert.equal(result.stdout.includes(endpoint), false);
    assert.equal(Object.hasOwn(report.shell.receipt, 'connection'), false);
  }
});

test('rejects malformed or inconsistent legacy shell endpoint evidence', (t) => {
  const f = fixture(t);
  const receipt = f.report.shell.receipt;
  delete receipt.transport;
  for (const endpoint of ['example.com:43112', '192.0.2.014:43112', '192.0.2.14:65536', '[2001:db8::1]:0']) {
    receipt.endpoint = endpoint;
    receipt.connection = `connected to ${endpoint}`;
    f.write();
    rejected(f.run(), /shell.*transport/i);
  }
  receipt.endpoint = '192.0.2.14:43112';
  receipt.connection = 'connected to 192.0.2.99:43112';
  f.write();
  rejected(f.run(), /shell.*transport/i);
  receipt.connection = `connected to ${receipt.endpoint}`;
  receipt.transport = 'usb';
  f.write();
  rejected(f.run(), /shell.*transport/i);
});

test('rejects traversal, URLs, file aliases, and reusing a receipt filename', (t) => {
  const f = fixture(t);
  for (const file of ['../kiosk.json', 'nested/kiosk.json', 'nested\\kiosk.json', 'https://example.com/kiosk.json',
    'file:kiosk.json', 'kiosk.json:private', 'kiosk~1.json', 'CON.json', 'kiosk.json. ', 'kiosk.json\n', 'manifest.json', 'QFM.JSON']) {
    f.manifest.kiosk.file = file;
    f.write();
    rejected(f.run(), /file/i);
  }
  rejected(execute('https://example.com/manifest.json'), /manifest/i);
});

test('rejects malformed or oversized source data with fixed errors', (t) => {
  const f = fixture(t);
  f.write();
  const qfmPath = path.join(f.dir, 'qfm.json');
  fs.writeFileSync(qfmPath, 'private-unprinted-malformed');
  rejected(f.run(), /qfm/i);
  fs.writeFileSync(qfmPath, ' '.repeat(1024 * 1024 + 1));
  rejected(f.run(), /qfm/i);
  f.report.qfm.receipt.kiosk.State.WifiAdbEnabled = 'private-unprinted-invalid';
  f.write();
  rejected(f.run(), /qfm/i);
});

test('rejects receipt hard links', (t) => {
  const f = fixture(t);
  const receipt = path.join(f.dir, 'kiosk.json');
  const link = path.join(f.dir, 'alias.json');
  f.manifest.kiosk.file = 'alias.json';
  f.write();
  fs.linkSync(receipt, link);
  rejected(f.run(), /kiosk/i);
});

test('rejects receipt file symlinks', (t) => {
  const f = fixture(t);
  f.manifest.kiosk.file = 'alias.json';
  f.write();
  const receipt = path.join(f.dir, 'kiosk.json');
  const link = path.join(f.dir, 'alias.json');
  try {
    fs.symlinkSync(receipt, link, 'file');
  } catch (error) {
    if (['EPERM', 'EACCES', 'ENOSYS'].includes(error.code)) return t.skip('File symlinks unavailable on this host');
    throw error;
  }
  try {
    rejected(f.run(), /kiosk/i);
  } finally {
    fs.unlinkSync(link);
  }
});

test('rejects manifests reached through a symlinked parent', (t) => {
  const f = fixture(t);
  f.write();
  const parent = tempFolder(t);
  const aliasFolder = path.join(parent, 'alias');
  try {
    fs.symlinkSync(f.dir, aliasFolder, process.platform === 'win32' ? 'junction' : 'dir');
  } catch (error) {
    if (['EPERM', 'EACCES', 'ENOSYS'].includes(error.code)) return t.skip('Directory symlinks unavailable on this host');
    throw error;
  }
  try {
    rejected(execute(path.join(aliasFolder, 'manifest.json')), /manifest/i);
  } finally {
    assert.equal(fs.lstatSync(aliasFolder).isSymbolicLink(), true);
    if (process.platform === 'win32') fs.rmdirSync(aliasFolder);
    else fs.unlinkSync(aliasFolder);
  }
});
