const assert = require('node:assert/strict');
const test = require('node:test');
const { projectRecovery } = require('./recovery-status.js');
const { sampleReport } = require('./sample.js');

const now = Date.parse('2026-01-01T12:00:00Z');

test('a current boot request can wait for Wi-Fi without inferring approval or shell', () => {
  const report = sampleReport('wifi', now);
  const { boot, approval, shell, watchdog } = projectRecovery(report, now);
  assert.equal(boot.label, 'Waiting for Wi-Fi');
  assert.equal(approval.label, 'Unknown');
  assert.equal(shell.label, 'Unknown');
  assert.equal(watchdog.label, 'Unknown');
});

test('a witnessed protected prompt is the evidence for awaiting wearer approval', () => {
  const report = sampleReport('approval', now);
  assert.equal(projectRecovery(report, now).approval.label, 'Awaiting wearer approval');
  delete report.approval;
  assert.equal(projectRecovery(report, now).approval.label, 'Unknown');
  assert.equal(projectRecovery(report, now).boot.label, 'Request sent');
});

test('setting, shell, and watchdog have independent evidence', () => {
  const report = sampleReport('ready', now);
  let view = projectRecovery(report, now);
  assert.deepEqual([view.setting.label, view.shell.label, view.watchdog.label], [
    'Enabled', 'Authenticated shell', 'Watchdog active',
  ]);
  delete report.shell;
  view = projectRecovery(report, now);
  assert.deepEqual([view.setting.label, view.shell.label, view.watchdog.label], [
    'Enabled', 'Unknown', 'Watchdog active',
  ]);
});

test('a different headset, boot, or retained Android boot count cannot qualify', () => {
  const report = sampleReport('ready', now);
  report.qfm.serial = 'other-demo-headset';
  report.kiosk.receipt.state.last_boot_request.boot_count -= 1;
  report.watchdog.receipt.boot_id = '00000000-0000-4000-8000-000000000001';
  report.shell.receipt.boot = '00000000-0000-4000-8000-000000000002';
  const view = projectRecovery(report, now);
  assert.equal(view.setting.label, 'Different headset or boot');
  assert.equal(view.boot.label, 'Different boot');
  assert.equal(view.shell.label, 'Different headset or boot');
  assert.equal(view.watchdog.label, 'Different boot');
});

test('matching transport endpoint aliases cannot stand in for hardware identity', () => {
  for (const alias of ['192.0.2.14:43112', '[2001:db8::1]:43112', 'adb-demo._adb-tls-connect._tcp']) {
    const report = sampleReport('ready', now);
    report.context.serial = alias;
    for (const key of ['kiosk', 'approval', 'qfm', 'shell', 'watchdog']) report[key].serial = alias;
    report.shell.receipt.serial = alias;
    for (const card of Object.values(projectRecovery(report, now))) assert.equal(card.label, 'Unknown');
  }
});

test('stale, future, and untrusted receipt times do not assert readiness', () => {
  const report = sampleReport('ready', now);
  report.qfm.observed_at_ms = now - 120_001;
  report.approval.observed_at_ms = now + 1;
  report.kiosk.receipt.recorded_at_ms = now - 123_000;
  const view = projectRecovery(report, now);
  assert.equal(view.setting.label, 'Stale');
  assert.equal(view.approval.label, 'Time unavailable');
  assert.equal(view.boot.label, 'Stale');
  assert.equal(projectRecovery(report, now + 120_001).shell.label, 'Stale');
});

test('missing owner facts and unverified UID do not become active status', () => {
  const report = sampleReport('ready', now);
  report.watchdog.receipt.fresh = 'false';
  report.shell.receipt.uid = '0';
  report.kiosk.receipt.state.last_boot_request = null;
  let view = projectRecovery(report, now);
  assert.equal(view.watchdog.label, 'Watchdog poll stale');
  assert.equal(view.shell.label, 'Unverified');
  assert.equal(view.boot.label, 'No boot receipt');
  report.shell.receipt.uid = '2000';
  report.shell.receipt.verified = 'true';
  report.watchdog.receipt.fresh = 'true';
  delete report.watchdog.receipt.lease_held;
  view = projectRecovery(report, now);
  assert.equal(view.watchdog.label, 'Unknown');
  assert.equal(view.shell.label, 'Unverified');
});

test('projections contain fixed copy and timestamps, never private payload values', () => {
  const report = sampleReport('ready', now);
  report.context.serial = 'PRIVATE-DEMO-SERIAL';
  for (const key of ['kiosk', 'approval', 'qfm', 'shell', 'watchdog']) {
    report[key].serial = report.context.serial;
    report[key].receipt.message = 'PRIVATE-DEMO-MESSAGE';
  }
  report.shell.receipt.serial = report.context.serial;
  report.shell.receipt.endpoint = 'PRIVATE-DEMO-ENDPOINT';
  report.qfm.receipt.kiosk.State.OperatorBridgeEndpoint = 'PRIVATE-DEMO-QFM-ENDPOINT';
  report.watchdog.receipt.owner_id = 'PRIVATE-DEMO-OWNER';
  const output = projectRecovery(report, now);
  assert.equal(output.shell.label, 'Authenticated shell');
  assert.doesNotMatch(JSON.stringify(output), /PRIVATE-DEMO-|00000000-0000/);
});

test('malformed, blank and unsupported imports leave all evidence unknown', () => {
  for (const report of [null, [], { schema: 'unrelated.v1' }, { schema: 'rusty-kiosk.recovery-status.v1' }]) {
    for (const card of Object.values(projectRecovery(report, now))) assert.equal(card.label, 'Unknown');
  }
});
