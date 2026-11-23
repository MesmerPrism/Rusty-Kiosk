'use strict';

const fs = require('node:fs');
const { isIP } = require('node:net');
const path = require('node:path');
const { TextDecoder } = require('node:util');
const { normalizeBootId, validHardwareSerial, validBootOutcome } = require('./recovery-status.js');

const MANIFEST_SCHEMA = 'rusty-kiosk.recovery-manifest.v1';
const REPORT_SCHEMA = 'rusty-kiosk.recovery-status.v1';
const KIOSK_SCHEMA = 'rusty.kiosk.cli_result.v1';
const SOURCES = ['kiosk', 'qfm', 'approval', 'shell', 'watchdog'];
const SCOPE_FIELDS = ['serial', 'boot_id', 'observed_at_ms'];
const WATCHDOG_FLAGS = ['identity_matches', 'lease_held', 'owner_active', 'active', 'fresh'];
const MAX_BYTES = 1024 * 1024;
const MAX_TIMESTAMP_MS = 8_640_000_000_000_000;
const RESERVED = /^(?:con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i;
const RECEIPT_FILE = /^[a-z0-9_-]+(?:\.[a-z0-9_-]+)*\.json$/i;
const SHA256 = /^[a-f0-9]{64}$/;

class ReportError extends Error {}

function requireFact(valid, message) {
  if (!valid) throw new ReportError(message);
}

function record(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function closedRecord(value, fields) {
  return record(value) && Object.keys(value).every(key => fields.includes(key));
}

function validTimestamp(value) {
  return Number.isSafeInteger(value) && value > 0 && value <= MAX_TIMESTAMP_MS;
}

function validBootCount(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function checkedScope(input, label) {
  const boot = normalizeBootId(input.boot_id);
  requireFact(validHardwareSerial(input.serial) && boot !== null && validTimestamp(input.observed_at_ms),
    `Invalid ${label} capture identity or time.`);
  return { serial: input.serial, boot_id: boot, observed_at_ms: input.observed_at_ms };
}

function comparablePath(file) {
  return process.platform === 'win32' ? file.toLowerCase() : file;
}

function plainLocalPath(file) {
  let folder = path.dirname(file);
  for (;;) {
    if (!fs.lstatSync(folder).isDirectory()) return false;
    const parent = path.dirname(folder);
    if (parent === folder) break;
    folder = parent;
  }
  return comparablePath(fs.realpathSync.native(file)) === comparablePath(file);
}

function sameFile(original, current) {
  return current.isFile() && current.nlink === 1n && original.dev === current.dev
    && original.ino === current.ino && original.size === current.size
    && original.mtimeNs === current.mtimeNs && original.ctimeNs === current.ctimeNs;
}

function readLocalJson(file, label) {
  const message = `Could not read ${label} as a local UTF-8 JSON file under 1 MiB.`;
  try {
    const info = fs.lstatSync(file, { bigint: true });
    requireFact(info.isFile() && info.nlink === 1n && info.size < BigInt(MAX_BYTES) && plainLocalPath(file), message);
    const flags = fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW ?? 0) | (fs.constants.O_NONBLOCK ?? 0);
    const fd = fs.openSync(file, flags);
    try {
      requireFact(sameFile(info, fs.fstatSync(fd, { bigint: true })) && plainLocalPath(file), message);
      const bytes = Buffer.alloc(Number(info.size) + 1);
      let length = 0;
      while (length < bytes.length) {
        const count = fs.readSync(fd, bytes, length, bytes.length - length, null);
        if (count === 0) break;
        length += count;
      }
      requireFact(length === Number(info.size) && sameFile(info, fs.fstatSync(fd, { bigint: true })), message);
      return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(0, length)));
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    throw new ReportError(message);
  }
}

function checkedManifestPath(input) {
  const drive = process.platform === 'win32' && /^[a-z]:[/\\](?![/\\])/i.test(input);
  const withoutDrive = drive ? input.slice(2) : input;
  requireFact(typeof input === 'string' && input.length > 0 && !/^[/\\]{2}/.test(input)
    && !/[:\u0000-\u001f]/.test(withoutDrive), 'Choose a local manifest JSON file.');
  const file = path.resolve(input);
  const name = path.basename(file);
  requireFact(/\.json$/i.test(name) && !RESERVED.test(name), 'Choose a local manifest JSON file.');
  return file;
}

function checkedContext(input) {
  requireFact(closedRecord(input, [...SCOPE_FIELDS, 'boot_count']), 'Invalid manifest context.');
  const context = checkedScope(input, 'context');
  if (Object.hasOwn(input, 'boot_count')) {
    requireFact(input.boot_count === null || validBootCount(input.boot_count), 'Invalid context boot count.');
    context.boot_count = input.boot_count;
  }
  return context;
}

function checkedSource(input, key, context, usedFiles) {
  const fields = [...SCOPE_FIELDS, 'file'];
  if (key === 'shell') fields.push('transport');
  requireFact(closedRecord(input, fields), `Invalid ${key} capture.`);
  const scope = checkedScope(input, key);
  requireFact(scope.serial === context.serial && scope.boot_id === context.boot_id,
    `The ${key} capture belongs to a different headset or boot.`);
  const file = input.file;
  requireFact(typeof file === 'string' && file.length <= 128 && file === file.trim() && RECEIPT_FILE.test(file)
    && !RESERVED.test(file) && !usedFiles.has(file.toLowerCase()),
    `Choose a distinct sibling JSON filename for ${key}.`);
  usedFiles.add(file.toLowerCase());
  return { ...scope, file, transport: input.transport };
}

function kioskReceipt(raw, scope, context) {
  requireFact(raw.schema === KIOSK_SCHEMA && ['status', 'check-setup-helper'].includes(raw.command)
    && typeof raw.accepted === 'boolean' && typeof raw.completed === 'boolean'
    && validTimestamp(raw.recorded_at_ms) && record(raw.state), 'Invalid Kiosk status receipt.');
  const state = {};
  if (Object.hasOwn(raw.state, 'request_wifi_adb_after_boot')) {
    requireFact(typeof raw.state.request_wifi_adb_after_boot === 'boolean', 'Invalid Kiosk restart request reading.');
    state.request_wifi_adb_after_boot = raw.state.request_wifi_adb_after_boot;
  }
  const last = raw.state.last_boot_request;
  if (last != null) {
    requireFact(record(last) && (last.boot_count === -1 || validBootCount(last.boot_count)) && validBootOutcome(last.outcome),
      'Invalid Kiosk boot request reading.');
    requireFact(last.boot_count === -1 || context.boot_count == null || last.boot_count === context.boot_count,
      'The Kiosk receipt records a different Android boot.');
    state.last_boot_request = { boot_count: last.boot_count, outcome: last.outcome };
  } else {
    state.last_boot_request = null;
  }
  return {
    schema: KIOSK_SCHEMA, command: raw.command, accepted: raw.accepted,
    completed: raw.completed, recorded_at_ms: raw.recorded_at_ms, state,
  };
}

function qfmReceipt(raw) {
  const kiosk = raw.kiosk;
  if (kiosk === null) return { kiosk: null };
  requireFact(record(kiosk) && kiosk.Schema === KIOSK_SCHEMA && kiosk.Command === 'status'
    && typeof kiosk.Accepted === 'boolean' && typeof kiosk.Completed === 'boolean'
    && record(kiosk.State) && typeof kiosk.State.WifiAdbEnabled === 'boolean', 'Invalid QFM Kiosk status receipt.');
  return { kiosk: {
    Schema: KIOSK_SCHEMA, Command: 'status', Accepted: kiosk.Accepted, Completed: kiosk.Completed,
    State: { WifiAdbEnabled: kiosk.State.WifiAdbEnabled },
  } };
}

function approvalReceipt(raw) {
  requireFact(raw.source === 'wearer-observation' && ['prompt-visible', 'approved', 'declined'].includes(raw.state),
    'Invalid wearer approval observation.');
  return { source: 'wearer-observation', state: raw.state };
}

function verifiedLegacyConnection(raw) {
  const endpoint = raw.endpoint;
  if (raw.verified !== true || typeof endpoint !== 'string' || endpoint !== endpoint.trim()) return false;
  if (raw.connection !== `connected to ${endpoint}` && raw.connection !== `already connected to ${endpoint}`) {
    return false;
  }
  // isIP validates a literal locally. Hostnames and interface-scoped addresses are outside this legacy shape.
  const parts = /^(?:\[([a-f0-9:.]+)\]|([0-9.]+)):([1-9][0-9]{0,4})$/i.exec(endpoint);
  if (!parts || Number(parts[3]) > 65_535) return false;
  return parts[1] ? isIP(parts[1]) === 6 : isIP(parts[2]) === 4;
}

function shellReceipt(raw, scope) {
  const boot = normalizeBootId(raw.boot);
  requireFact(raw.serial === scope.serial && boot === scope.boot_id, 'The shell receipt belongs to a different headset or boot.');
  const hasWifiEvidence = Object.hasOwn(raw, 'transport') ? raw.transport === 'wifi-adb' : verifiedLegacyConnection(raw);
  requireFact(scope.transport === 'wifi-adb' && hasWifiEvidence,
    'The shell observation requires matching Wi-Fi ADB transport evidence.');
  const validUid = Number.isSafeInteger(raw.uid) && raw.uid >= 0 && raw.uid <= 4_294_967_295
    || typeof raw.uid === 'string' && raw.uid === raw.uid.trim()
      && /^(0|[1-9]\d{0,9})$/.test(raw.uid) && Number(raw.uid) <= 4_294_967_295;
  requireFact(typeof raw.verified === 'boolean' && (validUid || (raw.uid === null && !raw.verified)),
    'Invalid shell UID or verification reading.');
  return { transport: scope.transport, serial: scope.serial, boot, uid: raw.uid, verified: raw.verified };
}

function watchdogReceipt(raw, scope) {
  const boot = normalizeBootId(raw.boot_id);
  requireFact(boot === scope.boot_id && (!Object.hasOwn(raw, 'serial') || raw.serial === scope.serial),
    'The watchdog receipt belongs to a different headset or boot.');
  requireFact(validDigest(raw.jar_sha256) && (validDigest(raw.startup_jar_sha256) || raw.startup_jar_sha256 === 'unknown')
    && WATCHDOG_FLAGS.every(flag => raw[flag] === 'true' || raw[flag] === 'false'), 'Invalid watchdog status receipt.');
  return {
    boot_id: boot, jar_sha256: raw.jar_sha256, startup_jar_sha256: raw.startup_jar_sha256,
    ...Object.fromEntries(WATCHDOG_FLAGS.map(flag => [flag, raw[flag]])),
  };
}

function validDigest(value) {
  return typeof value === 'string' && value.length === 64 && SHA256.test(value);
}

const REDACT = { kiosk: kioskReceipt, qfm: qfmReceipt, approval: approvalReceipt, shell: shellReceipt, watchdog: watchdogReceipt };

function buildReport(input) {
  const manifestFile = checkedManifestPath(input);
  const manifest = readLocalJson(manifestFile, 'manifest');
  requireFact(closedRecord(manifest, ['schema', 'context', ...SOURCES]) && manifest.schema === MANIFEST_SCHEMA,
    'Unsupported manifest or manifest fields.');
  const context = checkedContext(manifest.context);
  const usedFiles = new Set([path.basename(manifestFile).toLowerCase()]);
  const scopes = SOURCES.filter(key => Object.hasOwn(manifest, key))
    .map(key => [key, checkedSource(manifest[key], key, context, usedFiles)]);
  const report = { schema: REPORT_SCHEMA, context };
  for (const [key, scope] of scopes) {
    const raw = readLocalJson(path.join(path.dirname(manifestFile), scope.file), `${key} receipt`);
    requireFact(record(raw), `Invalid ${key} receipt.`);
    const receipt = REDACT[key](raw, scope, context);
    report[key] = { serial: scope.serial, boot_id: scope.boot_id, observed_at_ms: scope.observed_at_ms, receipt };
  }
  return report;
}

try {
  const args = process.argv.slice(2);
  requireFact(args.length === 1, 'Usage: node build-recovery-report.cjs <manifest.json>');
  process.stdout.write(`${JSON.stringify(buildReport(args[0]), null, 2)}\n`);
} catch (error) {
  const message = error instanceof ReportError ? error.message : 'Could not build the recovery report.';
  process.stderr.write(`Recovery report: ${message}\n`);
  process.exitCode = 1;
}
