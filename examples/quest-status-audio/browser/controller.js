import "./vendor/gatt-lifetime.js";
import {
  COMMAND_UUID, DETAILS_UUID, decodeHeadsetDetails, decodeResult, encodeRequest, identityBytes, identityHex,
  isIdentity, Opcode, parsePairingCode, RESULT_UUID, Result, SERVICE_UUID,
} from "./protocol.js";

export const REQUEST_KEY = "quest-status-audio:unresolved-v1";
const INVALID_PENDING = Symbol("invalid pending request");
const SETUP_TIMEOUT_MS = 45_000;
const TRANSFER_TIMEOUT_MS = 10_000;
const { OperationQueue } = globalThis.QuestBleLifetime;
const RESULT_VERBS = {
  [Result.OK]: "confirmed",
  [Result.REJECTED]: "rejected",
  [Result.FAILED]: "reported a failure for",
};

class BluetoothTimeout extends Error {}

function validDeviceId(id) {
  return typeof id === "string" && id.length > 0 && id.length <= 256 && !/[\u0000-\u001f\u007f]/.test(id);
}

function readJournal(storage) {
  try {
    const raw = storage?.getItem(REQUEST_KEY);
    if (raw == null) return null;
    const item = JSON.parse(raw);
    if (item && Object.keys(item).length === 4 && isIdentity(item.id) && isIdentity(item.epoch)
        && validDeviceId(item.deviceId) && (item.opcode === Opcode.PLAY || item.opcode === Opcode.STOP)) {
      return item;
    }
  } catch {
    // A damaged journal is still evidence that a prior outcome may be unknown.
  }
  return INVALID_PENDING;
}

function withDeadline(operation, ms) {
  let timer;
  const deadline = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new BluetoothTimeout("Bluetooth operation timed out")), ms);
  });
  return Promise.race([operation, deadline]).finally(() => clearTimeout(timer));
}

export class CompanionController {
  constructor({ bluetooth, storage, randomBytes = () => crypto.getRandomValues(new Uint8Array(16)),
    wallTime = () => Date.now(), elapsedTime = () => performance.now(), onChange = () => {} }) {
    Object.assign(this, { bluetooth, storage, randomBytes, wallTime, elapsedTime, onChange });
    this.link = null;
    this.pending = readJournal(storage);
    this.state = { phase: "disconnected", playback: null, note: "", busy: false, hasPending: false,
      details: null, detailsStatus: "unavailable", remainingSeconds: null, sessionExpired: false };
    if (this.pending) this.publish({ note: "A previous playback request has an unknown outcome." });
  }

  publish(changes) {
    this.state = { ...this.state, ...changes, hasPending: this.pending !== null };
    this.onChange(this.state);
  }

  requireCurrent(link) {
    if (this.link !== link) throw new Error("Bluetooth connection ended");
  }

  retire(link, note, sessionExpired = false) {
    if (this.link !== link) return;
    this.link = null;
    link.device?.removeEventListener("gattserverdisconnected", link.disconnected);
    if (link.device?.gatt.connected) link.device.gatt.disconnect();
    this.publish({ phase: "disconnected", playback: null, busy: false, note, sessionExpired,
      detailsStatus: this.state.details ? "stale" : "unavailable", remainingSeconds: null });
  }

  disconnect() {
    if (this.link) this.retire(this.link, this.pending ? "The previous playback request is still unknown." : "");
  }

  tick() {
    const link = this.link;
    if (link?.deadline == null) return;
    const remainingSeconds = Math.max(0, Math.ceil((link.deadline - this.elapsedTime()) / 1000));
    if (remainingSeconds === 0) {
      const note = "Session time elapsed. Start a new session on the headset for a new code.";
      this.retire(link, this.pending ? `${note} The previous playback request is still unknown.` : note, true);
    } else if (this.state.remainingSeconds !== remainingSeconds) {
      this.publish({ remainingSeconds });
    }
  }

  async connect(code) {
    if (this.link) return;
    if (!this.bluetooth?.requestDevice) {
      this.publish({ note: "Use a browser that supports Web Bluetooth on this site." });
      return;
    }
    const secret = parsePairingCode(code);
    if (!secret) {
      this.publish({ note: "Enter the 32-character pairing code shown on the headset." });
      return;
    }

    const link = {};
    link.queue = new OperationQueue(() => this.requireCurrent(link));
    this.link = link;
    this.publish({ phase: "connecting", busy: true, playback: null, note: "", details: null,
      detailsStatus: "unavailable", remainingSeconds: null, sessionExpired: false });

    try {
      // Invoke the chooser before yielding to preserve the browser's click activation.
      const device = await this.bluetooth.requestDevice({ filters: [{ services: [SERVICE_UUID] }] });
      this.requireCurrent(link);
      if (!device.gatt || !validDeviceId(device.id)) throw new Error("Invalid Bluetooth device");
      link.device = device;
      link.disconnected = () => {
        this.tick();
        const note = this.pending ? "Connection lost. The previous playback request is still unknown." : "Connection lost.";
        this.retire(link, note);
      };
      device.addEventListener("gattserverdisconnected", link.disconnected);

      const connecting = device.gatt.connect().then((gatt) => {
        if (this.link !== link && this.link?.device?.gatt !== device.gatt && device.gatt.connected) {
          device.gatt.disconnect();
        }
        return gatt;
      });
      const gatt = await withDeadline(connecting, SETUP_TIMEOUT_MS);
      this.requireCurrent(link);
      const service = await withDeadline(gatt.getPrimaryService(SERVICE_UUID), SETUP_TIMEOUT_MS);
      this.requireCurrent(link);
      link.service = service;
      link.write = await withDeadline(service.getCharacteristic(COMMAND_UUID), SETUP_TIMEOUT_MS);
      this.requireCurrent(link);
      link.read = await withDeadline(service.getCharacteristic(RESULT_UUID), SETUP_TIMEOUT_MS);

      const auth = await this.exchange(link, Opcode.AUTH, secret, SETUP_TIMEOUT_MS);
      secret.fill(0);
      this.requireCurrent(link);
      if (auth.opcode !== Opcode.AUTH || auth.result !== Result.OK || !isIdentity(auth.id)) {
        this.retire(link, "Pairing failed. Check the headset's current code or start a new session for a new code.");
        return;
      }
      link.epoch = auth.id;
      await this.readStatus(link);
      await this.readDetails(link);
      if (this.pending) await this.recover(link);
      this.requireCurrent(link);
      this.publish({ phase: "connected", busy: false });
    } catch {
      this.retire(link, this.pending
        ? "Connection interrupted. The previous playback request is still unknown."
        : "Could not pair with the headset. Check Bluetooth and try again.");
    } finally {
      secret.fill(0);
    }
  }

  async exchange(link, opcode, id, timeout = TRANSFER_TIMEOUT_MS) {
    const packet = encodeRequest(opcode, id);
    try {
      return await withDeadline(link.queue.run(link, async () => {
        await link.write.writeValueWithResponse(packet);
        this.requireCurrent(link);
        const view = await link.read.readValue();
        return decodeResult(view);
      }), timeout);
    } finally {
      packet.fill(0);
    }
  }

  newId() {
    const bytes = this.randomBytes();
    if (!(bytes instanceof Uint8Array) || bytes.length !== 16 || !isIdentity(identityHex(bytes))) throw new Error("Invalid generated identity");
    return bytes;
  }

  async readStatus(link) {
    const id = this.newId();
    const receipt = await this.exchange(link, Opcode.STATUS, id);
    this.requireCurrent(link);
    if (receipt.opcode !== Opcode.STATUS || receipt.id !== identityHex(id) || receipt.result !== Result.OK) {
      throw new Error("Status receipt did not match");
    }
    this.publish({ playback: receipt.playback });
  }

  async readDetails(link) {
    let observation;
    try {
      observation = await withDeadline(link.queue.run(link, async () => {
        if (link.detailsChannel === undefined) {
          try {
            link.detailsChannel = await link.service.getCharacteristic(DETAILS_UUID);
          } catch (error) {
            if (error.name !== "NotFoundError") throw error;
            link.detailsChannel = null;
          }
          this.requireCurrent(link);
        }
        if (!link.detailsChannel) return null;
        const requestedAt = this.elapsedTime();
        const snapshot = decodeHeadsetDetails(await link.detailsChannel.readValue());
        return { snapshot, requestedAt };
      }), TRANSFER_TIMEOUT_MS);
    } catch (error) {
      if (error instanceof BluetoothTimeout) throw error;
    }

    this.requireCurrent(link);
    if (!observation || observation.snapshot.epoch !== link.epoch) {
      this.publish({ detailsStatus: this.state.details ? "stale" : "unavailable" });
      return;
    }

    const { battery, charging, remainingSeconds } = observation.snapshot;
    link.deadline = observation.requestedAt + remainingSeconds * 1000;
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
      await this.readDetails(link);
      this.requireCurrent(link);
      this.publish({ busy: false });
    } catch {
      this.retire(link, this.pending ? "Status unavailable. The previous playback request is still unknown." : "Headset status unavailable.");
    }
  }

  clearPending() {
    try {
      this.storage.removeItem(REQUEST_KEY);
      this.pending = null;
      return true;
    } catch {
      return false;
    }
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
    if (entry === INVALID_PENDING || entry.epoch !== link.epoch || entry.deviceId !== link.device.id) {
      this.publish({ note: "The saved request could not be matched to this headset session. Its outcome is unknown." });
      return;
    }
    const receipt = await this.exchange(link, Opcode.LOOKUP, identityBytes(entry.id));
    this.requireCurrent(link);
    this.settle(entry, receipt, true);
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
    this.publish({
      playback: previous ? this.state.playback : receipt.playback,
      note: cleared ? note : `${note} This browser could not clear the saved request.`,
    });
  }

  async #effect(opcode) {
    this.tick();
    const link = this.link;
    if (this.state.phase !== "connected" || this.state.busy || this.pending) return;
    let id;
    try {
      id = this.newId();
      this.startJournal({ opcode, id: identityHex(id), epoch: link.epoch, deviceId: link.device.id });
    } catch {
      this.publish({ note: "This browser could not store the request. No playback command was sent." });
      return;
    }
    const entry = this.pending;
    this.publish({ busy: true, note: "" });
    try {
      const receipt = await this.exchange(link, opcode, id);
      this.requireCurrent(link);
      this.settle(entry, receipt);
      this.publish({ busy: false });
    } catch {
      this.retire(link, "Connection interrupted. The playback request has an unknown outcome.");
    }
  }

  play() { return this.#effect(Opcode.PLAY); }
  stop() { return this.#effect(Opcode.STOP); }
}
