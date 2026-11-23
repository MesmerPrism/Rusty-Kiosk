(function exposeSample(exports) {
  'use strict';

  function sampleReport(scenario, now = Date.now()) {
    const serial = 'demo-headset';
    const bootId = '00000000-0000-4000-8000-000000000100';
    const scope = { serial, boot_id: bootId, observed_at_ms: now };
    const report = {
      schema: 'rusty-kiosk.recovery-status.v1',
      context: { ...scope, boot_count: 12 },
      kiosk: { ...scope, receipt: {
        schema: 'rusty.kiosk.cli_result.v1', command: 'status', accepted: true, completed: true,
        recorded_at_ms: now,
        state: {
          request_wifi_adb_after_boot: true,
          last_boot_request: { boot_count: 12, outcome: scenario === 'wifi' ? 'waiting_for_wifi' : 'requested' },
        },
      } },
      qfm: { ...scope, receipt: {
        installation: { HostOperatorAvailable: true },
        kiosk: {
          Schema: 'rusty.kiosk.cli_result.v1', Command: 'status', Accepted: true, Completed: true,
          State: { WifiAdbEnabled: scenario === 'ready' },
        },
      } },
    };
    if (scenario === 'approval' || scenario === 'ready') {
      report.approval = { ...scope, receipt: {
        source: 'wearer-observation', state: scenario === 'approval' ? 'prompt-visible' : 'approved',
      } };
    }
    if (scenario === 'ready') {
      report.shell = { ...scope, receipt: { transport: 'wifi-adb', serial, boot: bootId, uid: '2000', verified: true } };
      const sampleHash = 'a'.repeat(64);
      report.watchdog = { ...scope, receipt: {
        boot_id: bootId, jar_sha256: sampleHash, startup_jar_sha256: sampleHash,
        identity_matches: 'true', lease_held: 'true', owner_active: 'true', active: 'true', fresh: 'true',
      } };
    }
    return report;
  }

  exports.sampleReport = sampleReport;
})(typeof module === 'object' ? module.exports : (globalThis.QuestRecoverySamples = {}));
