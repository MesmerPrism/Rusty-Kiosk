import { pairPhone } from "./vendor/quest-pairing.js";
import { discoverHeadset, endpoint, GatewayError, openGateway, validEnrollment } from "./quest-link.js";
import { decodeHeadsetDetails, decodeResult, identityHex, isIdentity, Opcode, Result, SERVICE_UUID } from "./protocol.js";

export const REQUEST_KEY = "quest-status-audio:unresolved-v1";
const SELECTED_KEY = "quest-status-audio:selected-headset";
const INVALID_PENDING = Symbol("invalid pending request");
const RETRY_MS = 8000;
const STATUS_PROBE_MS = 8000;
const enrollmentKey = device => `quest-status-audio:enrollment/${device}`;
const RESULT_VERBS = {
  [Result.OK]: "confirmed",
  [Result.REJECTED]: "rejected",
  [Result.FAILED]: "reported a failure for",
};

function validBrowserId(id) {
  return typeof id === "string" && id.length > 0 && id.length <= 256 && !/[\u0000-\u001f\u007f]/.test(id);
}

function readJournal(storage) {
  try {
    const raw = storage?.getItem(REQUEST_KEY);
    if (raw == null) return null;
    const item = JSON.parse(raw);
    if (item && Object.keys(item).length === 4 && isIdentity(item.id) && isIdentity(item.epoch)
      && isIdentity(item.installation) && (item.opcode === Opcode.PLAY || item.opcode === Opcode.STOP)) return item;
  } catch {
    // An unreadable journal is still evidence that a prior outcome may be unknown.
  }
  return INVALID_PENDING;
}

function readEnrollment(storage, device) {
  try {
    const id = device ?? storage?.getItem(SELECTED_KEY);
    if (!isIdentity(id)) return null;
    const record = JSON.parse(storage?.getItem(enrollmentKey(id)));
    if (record?.device !== id || !validEnrollment(record.enrollment)) return null;
    return { device: id, address: endpoint(record.address), enrollment: record.enrollment,
      bluetoothId: validBrowserId(record.bluetoothId) ? record.bluetoothId : undefined };
  } catch { return null; }
}

function frame(value, length) {
  if (typeof value !== "string" || value.length !== 2 * length || !/^[a-f0-9]+$/.test(value)) {
    throw new Error("Invalid headset frame.");
  }
  const bytes = Uint8Array.from(value.match(/../g), byte => Number.parseInt(byte, 16));
  return new DataView(bytes.buffer);
}

export class CompanionController {
  constructor({ bluetooth, storage, enrollmentStorage, fetcher = fetch,
    randomBytes = () => crypto.getRandomValues(new Uint8Array(16)), wallTime = () => Date.now(),
    elapsedTime = () => performance.now(), onChange = () => {} }) {
    Object.assign(this, { bluetooth, storage, enrollmentStorage, fetcher, randomBytes, wallTime, elapsedTime, onChange });
    this.link = null;
    this.saved = readEnrollment(enrollmentStorage);
    this.pending = readJournal(storage);
    this.comparison = null;
    this.auto = true;
    this.retryAt = 0;
    this.state = { phase: "disconnected", stage: null, playback: null, note: "", busy: false, hasPending: false,
      details: null, detailsStatus: "unavailable", remainingSeconds: null, sessionExpired: false,
      pairingCode: null, remembered: Boolean(this.saved) };
    if (this.pending) this.publish({ note: "A previous playback request has an unknown outcome." });
  }

  publish(changes) {
    this.state = { ...this.state, ...changes, hasPending: this.pending !== null, remembered: Boolean(this.saved) };
    this.onChange(this.state);
  }

  requireCurrent(link) {
    if (this.link !== link) throw new Error("Headset connection ended.");
    link.abort.signal.throwIfAborted();
  }

  retire(link, note, sessionExpired = false) {
    if (this.link !== link) return;
    this.link = null;
    link.abort.abort();
    if (this.comparison?.link === link) this.answerPairing(false);
    this.retryAt = this.elapsedTime() + RETRY_MS;
    this.publish({ phase: "disconnected", stage: null, pairingCode: null, playback: null, busy: false,
      note, sessionExpired, detailsStatus: this.state.details ? "stale" : "unavailable", remainingSeconds: null });
  }

  disconnect() {
    this.auto = false;
    this.suspend();
  }

  suspend() {
    if (this.link) this.retire(this.link, this.pending ? "The previous playback request is still unknown." : "");
  }

  resumeSaved() {
    this.auto = true;
    return this.reconnect({ immediate: true });
  }

  forgetHeadset() {
    this.disconnect();
    if (!this.saved) return;
    if (this.enrollmentStorage) {
      try {
        this.enrollmentStorage.removeItem(enrollmentKey(this.saved.device));
        this.enrollmentStorage.removeItem(SELECTED_KEY);
      } catch {
        this.publish({ note: "This browser could not remove the saved headset." });
        return;
      }
    }
    this.saved = null;
    this.publish({ note: "Headset forgotten on this browser. Use the headset app to revoke access." });
  }

  tick() {
    const link = this.link;
    if (link?.deadline == null) return;
    const remainingSeconds = Math.max(0, Math.ceil((link.deadline - this.elapsedTime()) / 1000));
    if (remainingSeconds === 0) {
      const note = "Session time elapsed. Start a new session on the headset.";
      this.retire(link, this.pending ? `${note} The previous playback request is still unknown.` : note, true);
    } else if (this.state.remainingSeconds !== remainingSeconds) {
      this.publish({ remainingSeconds });
    }
  }

  begin(stage) {
    const link = { abort: new AbortController() };
    this.link = link;
    this.publish({ phase: "connecting", stage, busy: true, playback: null, note: "", pairingCode: null,
      detailsStatus: this.state.details ? "stale" : "unavailable", remainingSeconds: null, sessionExpired: false });
    return link;
  }

  updateStage(link, stage) {
    this.requireCurrent(link);
    this.publish({ stage });
  }

  remember(record) {
    this.saved = record;
    if (!this.enrollmentStorage) return false;
    try {
      this.enrollmentStorage.setItem(enrollmentKey(record.device), JSON.stringify(record));
      this.enrollmentStorage.setItem(SELECTED_KEY, record.device);
      return true;
    } catch { return false; }
  }

  compare(link, { code, signal }) {
    this.requireCurrent(link);
    signal.throwIfAborted();
    return new Promise(resolve => {
      this.comparison = { link, resolve };
      this.publish({ pairingCode: code, note: "Compare the digits, then confirm on both devices." });
    });
  }

  answerPairing(approved) {
    const comparison = this.comparison;
    if (!comparison) return;
    this.comparison = null;
    comparison.resolve(approved === true);
    if (this.link === comparison.link) {
      this.publish({ pairingCode: null, note: approved ? "Waiting for confirmation on the headset." : "" });
    }
  }

  async connect() {
    if (this.link || !this.bluetooth?.requestDevice) return;
    this.auto = true;
    const link = this.begin("discovering");
    try {
      // The explicit click supplies browser activation for this chooser.
      const device = await this.bluetooth.requestDevice({ filters: [{ services: [SERVICE_UUID] }] });
      this.requireCurrent(link);
      const seen = await discoverHeadset(device, link.abort.signal);
      this.updateStage(link, "network");
      const gateway = await openGateway(seen.endpoint, { installation: seen.owner.device, seen: seen.owner,
        signal: link.abort.signal, fetcher: this.fetcher });
      this.requireCurrent(link);
      const saved = this.saved?.device === gateway.owner.device ? this.saved
        : readEnrollment(this.enrollmentStorage, gateway.owner.device);
      const record = { device: gateway.owner.device, address: seen.endpoint, bluetoothId: device.id };
      if (saved) {
        try {
          await this.attach(link, gateway, { ...record, enrollment: saved.enrollment });
          return;
        } catch (error) {
          if (!(error instanceof GatewayError) || error.status !== 403) throw error;
        }
      }
      this.updateStage(link, "pairing");
      const ticket = await pairPhone({ owner: gateway.owner, signal: link.abort.signal, transport: gateway.pairing,
        onCode: prompt => this.compare(link, prompt) });
      this.requireCurrent(link);
      const enrolled = { ...record, enrollment: { controller: ticket.controller, key: ticket.key } };
      // Both devices already approved enrollment. Preserve it even if the first status reply is lost.
      this.remember(enrolled);
      await this.attach(link, gateway, enrolled);
    } catch (error) {
      this.fail(link, error);
    }
  }

  async reconnect({ immediate = false } = {}) {
    if (this.link || !this.auto || !this.saved || (!immediate && this.elapsedTime() < this.retryAt)) return;
    const saved = this.saved;
    const link = this.begin("reconnecting");
    try {
      try {
        await this.attachSaved(link, saved);
      } catch (error) {
        this.requireCurrent(link);
        if (error instanceof GatewayError && error.status === 403) throw error;
        if (!saved.bluetoothId || !this.bluetooth?.getDevices) throw error;
        const devices = await this.bluetooth.getDevices();
        this.requireCurrent(link);
        const device = devices.find(item => item.id === saved.bluetoothId);
        if (!device) throw error;
        const seen = await discoverHeadset(device, link.abort.signal);
        if (seen.owner.device !== saved.device) throw new Error("The granted Bluetooth device is not the saved headset.");
        await this.attachSaved(link, { ...saved, address: seen.endpoint }, seen.owner);
      }
    } catch (error) {
      this.fail(link, error, true);
    }
  }

  async attachSaved(link, record, seen) {
    const gateway = await openGateway(record.address, { installation: record.device, seen,
      signal: link.abort.signal, fetcher: this.fetcher });
    await this.attach(link, gateway, record);
  }

  async attach(link, gateway, record) {
    this.requireCurrent(link);
    this.publish({ stage: "authenticating", note: "" });
    link.installation = gateway.owner.device;
    link.exchange = await gateway.authenticate(record.enrollment);
    await this.readStatus(link);
    this.requireCurrent(link);
    const persisted = this.remember(record);
    if (this.pending) await this.recover(link);
    this.requireCurrent(link);
    this.publish({ phase: "connected", stage: null, busy: false, pairingCode: null,
      note: this.state.note || (persisted ? "" : "This browser could not save the headset for automatic reconnect.") });
  }

  fail(link, error, automatic = false) {
    if (this.link !== link) return;
    const stage = this.state.stage;
    let note;
    if (automatic) {
      note = error instanceof GatewayError && error.status === 403
        ? "This browser’s enrollment was rejected. Select the headset to pair again."
        : "Saved headset unavailable. Start its session or select it again to refresh its address.";
    } else if (stage === "discovering") {
      note = error?.name === "NotFoundError"
        ? "No headset selected. Start a session and try Connect headset again."
        : "Bluetooth discovery failed. Check Bluetooth permissions and keep the headset session running.";
    } else if (stage === "network") {
      note = "The headset’s local address could not be reached or verified. Use the same network and allow local network access in the browser.";
    } else if (stage === "pairing") {
      note = error instanceof TypeError ? "Pairing lost the local network connection." : error?.message || "Pairing ended.";
    } else {
      note = "Headset status could not be authenticated. Check the active headset session.";
    }
    this.retire(link, this.pending ? `${note} The previous playback request is still unknown.` : note);
  }

  newId() {
    const bytes = this.randomBytes();
    const id = bytes instanceof Uint8Array ? identityHex(bytes) : "";
    if (!isIdentity(id)) throw new Error("Invalid generated identity.");
    return id;
  }

  async readStatus(link) {
    const id = this.newId();
    const requestedAt = this.elapsedTime();
    const response = await link.exchange("status", { id });
    this.requireCurrent(link);
    const receipt = decodeResult(frame(response.result, 20));
    if (receipt.opcode !== Opcode.STATUS || receipt.id !== id || receipt.result !== Result.OK) {
      throw new Error("Status receipt did not match.");
    }
    link.lastStatusAt = this.elapsedTime();
    this.publish({ playback: receipt.playback });
    let snapshot;
    try { snapshot = decodeHeadsetDetails(frame(response.details, 21)); }
    catch { /* Keep the last confirmed details if this observation was invalid. */ }
    if (!snapshot || (link.epoch && snapshot.epoch !== link.epoch)) {
      this.publish({ detailsStatus: this.state.details ? "stale" : "unavailable" });
      return;
    }
    link.epoch = snapshot.epoch;
    const { battery, charging, remainingSeconds } = snapshot;
    link.deadline = requestedAt + remainingSeconds * 1000;
    this.publish({ details: { battery, charging, remainingSeconds, confirmedAt: this.wallTime() },
      detailsStatus: "current" });
    this.tick();
    this.requireCurrent(link);
  }

  async refresh() {
    this.tick();
    const link = this.link;
    if (this.state.phase !== "connected" || this.state.busy) return;
    this.publish({ busy: true });
    try {
      await this.readStatus(link);
      this.requireCurrent(link);
      this.publish({ busy: false });
    } catch {
      this.retire(link, this.pending ? "Status unavailable. The previous playback request is still unknown." : "Headset status unavailable.");
    }
  }

  probeStatus() {
    if (this.state.phase === "connected" && this.elapsedTime() - this.link.lastStatusAt >= STATUS_PROBE_MS) {
      return this.refresh();
    }
  }

  clearPending() {
    try {
      this.storage.removeItem(REQUEST_KEY);
      this.pending = null;
      return true;
    } catch { return false; }
  }

  dismissPending() {
    if (!this.pending || this.state.busy) return;
    const cleared = this.clearPending();
    this.publish({ note: cleared ? "Saved request dismissed. Its outcome remains unknown." : "This browser could not clear the saved request." });
  }

  startJournal(entry) {
    this.storage.setItem(REQUEST_KEY, JSON.stringify(entry));
    this.pending = entry;
  }

  async recover(link) {
    const entry = this.pending;
    if (entry === INVALID_PENDING || entry.epoch !== link.epoch || entry.installation !== link.installation) {
      this.publish({ note: "The saved request could not be matched to this headset session. Its outcome is unknown." });
      return;
    }
    const response = await link.exchange("receipt", { id: entry.id });
    this.requireCurrent(link);
    this.settle(entry, decodeResult(frame(response.result, 20)), true);
  }

  settle(entry, receipt, previous = false) {
    if (receipt.opcode !== entry.opcode || receipt.id !== entry.id || receipt.result === Result.UNKNOWN) {
      this.publish({ note: "The previous playback request has an unknown outcome." });
      return;
    }
    const action = entry.opcode === Opcode.PLAY ? "play" : "stop";
    const description = previous ? `previous ${action}` : action;
    const note = `The headset ${RESULT_VERBS[receipt.result]} the ${description} request.`;
    const cleared = this.clearPending();
    this.publish({ playback: previous ? this.state.playback : receipt.playback,
      note: cleared ? note : `${note} This browser could not clear the saved request.` });
  }

  async #effect(opcode) {
    this.tick();
    const link = this.link;
    if (this.state.phase !== "connected" || this.state.busy || this.pending) return;
    if (!link.epoch) {
      this.publish({ note: "The current headset session could not be verified. Refresh its status before controlling playback." });
      return;
    }
    let id;
    try {
      id = this.newId();
      this.startJournal({ opcode, id, epoch: link.epoch, installation: link.installation });
    } catch {
      this.publish({ note: "This browser could not store the request. No playback command was sent." });
      return;
    }
    const entry = this.pending;
    this.publish({ busy: true, note: "" });
    try {
      const response = await link.exchange("command", { opcode, id });
      this.requireCurrent(link);
      this.settle(entry, decodeResult(frame(response.result, 20)));
      this.publish({ busy: false });
    } catch {
      this.retire(link, "Connection interrupted. The playback request has an unknown outcome.");
    }
  }

  play() { return this.#effect(Opcode.PLAY); }
  stop() { return this.#effect(Opcode.STOP); }
}
