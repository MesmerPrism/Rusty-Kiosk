(function exposeRecoveryStatus(exports) {
  'use strict';

  const SCHEMA = 'rusty-kiosk.recovery-status.v1';
  const KIOSK_SCHEMA = 'rusty.kiosk.cli_result.v1';
  const FRESH_MS = 120_000;
  const BOOT_ID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
  const EMPTY_BOOT = '00000000-0000-0000-0000-000000000000';
  const SHA256 = /^[a-f0-9]{64}$/;
  const HARDWARE_SERIAL = /^[a-z0-9][a-z0-9_-]{0,127}$/i;
  const BOOT_LABELS = new Map([
    ['opted_out', ['Restart request off', 'neutral']],
    ['no_authority', ['Setup authority unavailable', 'attention']],
    ['waiting_for_wifi', ['Waiting for Wi-Fi', 'attention']],
    ['requested', ['Request sent', 'neutral']],
    ['failed', ['Request failed', 'attention']],
    ['expired', ['Request expired', 'attention']],
    ['cancelled', ['Request cancelled', 'neutral']],
    ['network_unavailable', ['Wi-Fi unavailable at dispatch', 'attention']],
    ['dispatch_started', ['Dispatch outcome unknown', 'attention']],
  ]);
  const APPROVAL_LABELS = new Map([
    ['prompt-visible', ['Awaiting wearer approval', 'attention']],
    ['approved', ['Wearer approved', 'confirmed']],
    ['declined', ['Wearer declined', 'attention']],
  ]);

  function record(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }

  function bootId(value) {
    return typeof value === 'string' && BOOT_ID.test(value) && value !== EMPTY_BOOT
      ? value.toLowerCase()
      : null;
  }

  function serial(value) {
    return typeof value === 'string' && HARDWARE_SERIAL.test(value);
  }

  function card(label = 'Unknown', tone = 'neutral', observedAt = null) {
    return { label, tone, observedAt };
  }

  function timeProblem(observedAt, now) {
    if (!Number.isSafeInteger(observedAt) || observedAt <= 0 || observedAt > now) {
      return card('Time unavailable', 'attention');
    }
    return now - observedAt > FRESH_MS ? card('Stale', 'attention', observedAt) : null;
  }

  function readScoped(source, context, now, project) {
    if (!record(source) || !record(source.receipt)) return card();
    const sourceBoot = bootId(source.boot_id);
    if (!serial(source.serial) || sourceBoot === null) return card();
    if (source.serial !== context.serial || sourceBoot !== bootId(context.boot_id)) {
      return card('Different headset or boot', 'attention');
    }
    return timeProblem(context.observed_at_ms, now) ?? timeProblem(source.observed_at_ms, now)
      ?? project(source.receipt, source.observed_at_ms, context, now);
  }

  function projectBoot(receipt, at, context, now) {
    if (receipt.schema !== KIOSK_SCHEMA || !['status', 'check-setup-helper'].includes(receipt.command)
        || receipt.accepted !== true || receipt.completed !== true || !record(receipt.state)) return card();
    const receiptTimeProblem = timeProblem(receipt.recorded_at_ms, now);
    if (receiptTimeProblem) return receiptTimeProblem;
    const last = receipt.state.last_boot_request;
    if (last == null) return card('No boot receipt', 'neutral', at);
    if (!record(last) || !Number.isSafeInteger(last.boot_count) || last.boot_count < 0
        || !Number.isSafeInteger(context.boot_count) || context.boot_count < 0) return card();
    if (last.boot_count !== context.boot_count) return card('Different boot', 'attention');
    if (last.outcome === 'waiting_for_wifi' && receipt.state.request_wifi_adb_after_boot === false) {
      return card('Restart request off', 'neutral', receipt.recorded_at_ms);
    }
    const value = BOOT_LABELS.get(last.outcome);
    return value ? card(...value, receipt.recorded_at_ms) : card();
  }

  function projectApproval(receipt, at) {
    const value = receipt.source === 'wearer-observation' && APPROVAL_LABELS.get(receipt.state);
    return value ? card(...value, at) : card();
  }

  function projectSetting(receipt, at) {
    const kiosk = receipt.kiosk;
    if (!record(kiosk) || kiosk.Schema !== KIOSK_SCHEMA || kiosk.Command !== 'status'
        || kiosk.Accepted !== true || kiosk.Completed !== true || !record(kiosk.State)) return card();
    if (kiosk.State.WifiAdbEnabled === true) return card('Enabled', 'confirmed', at);
    if (kiosk.State.WifiAdbEnabled === false) return card('Disabled', 'neutral', at);
    return card();
  }

  function projectShell(receipt, at, context) {
    if (receipt.transport !== 'wifi-adb' || !serial(receipt.serial) || bootId(receipt.boot) === null) return card();
    if (receipt.serial !== context.serial || bootId(receipt.boot) !== bootId(context.boot_id)) {
      return card('Different headset or boot', 'attention');
    }
    if (receipt.verified === true && (receipt.uid === '2000' || receipt.uid === 2000)) {
      return card('Authenticated shell', 'confirmed', at);
    }
    return card('Unverified', 'attention', at);
  }

  function projectWatchdog(receipt, at, context) {
    if (bootId(receipt.boot_id) === null) return card();
    if (bootId(receipt.boot_id) !== bootId(context.boot_id)) return card('Different boot', 'attention');
    if (receipt.owner_active === 'true' && receipt.identity_matches === 'true' && receipt.lease_held === 'true') {
      if (receipt.active === 'true' && receipt.fresh === 'false') {
        return card('Watchdog poll stale', 'attention', at);
      }
      if (receipt.active === 'true' && receipt.fresh === 'true' && typeof receipt.jar_sha256 === 'string'
          && SHA256.test(receipt.jar_sha256) && receipt.startup_jar_sha256 === receipt.jar_sha256) {
        return card('Watchdog active', 'confirmed', at);
      }
    }
    return card();
  }

  function projectRecovery(report, now = Date.now()) {
    const context = report?.context;
    if (report?.schema !== SCHEMA || !record(context) || !serial(context.serial) || bootId(context.boot_id) === null) {
      return { boot: card(), approval: card(), setting: card(), shell: card(), watchdog: card() };
    }
    return {
      boot: readScoped(report.kiosk, context, now, projectBoot),
      approval: readScoped(report.approval, context, now, projectApproval),
      setting: readScoped(report.qfm, context, now, projectSetting),
      shell: readScoped(report.shell, context, now, projectShell),
      watchdog: readScoped(report.watchdog, context, now, projectWatchdog),
    };
  }

  exports.projectRecovery = projectRecovery;
})(typeof module === 'object' ? module.exports : (globalThis.QuestRecoveryStatus = {}));
