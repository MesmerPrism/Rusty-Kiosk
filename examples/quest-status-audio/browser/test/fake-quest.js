// Self-contained so browser tests can install it before the production entry point loads.
export function makeQuest({ code, epoch, deviceId = "fixture-quest" }) {
  const serviceId = "2e1fb001-2d5c-44ed-93de-9ecd76c520d6";
  const commandId = "2e1fb002-2d5c-44ed-93de-9ecd76c520d6";
  const resultId = "2e1fb003-2d5c-44ed-93de-9ecd76c520d6";
  const bytes = (hex) => Uint8Array.from(hex.match(/../g).map((pair) => Number.parseInt(pair, 16)));
  const hex = (id) => Array.from(id, (byte) => byte.toString(16).padStart(2, "0")).join("");
  const empty = new Uint8Array(16);
  const receipts = new Map();
  const listeners = new Set();
  const opcodes = [];
  let chooserCount = 0;
  let authorized = false;
  let playback = 0;
  let result;
  let failure;
  let mismatch;
  let held;

  function report(opcode, status, id = empty) {
    result = Uint8Array.of(1, opcode, status, playback, ...id);
  }

  const write = {
    async writeValueWithResponse(request) {
      if (!gatt.connected || request.length !== 18 || request[0] !== 1) throw new Error("Invalid fixture write");
      const opcode = request[1];
      const id = request.slice(2);
      opcodes.push(opcode);
      if (opcode === 0) {
        authorized = hex(id) === code;
        report(0, authorized ? 0 : 1, authorized ? bytes(epoch) : empty);
        return;
      }
      if (!authorized) throw new Error("Unauthenticated fixture write");
      if (opcode === 4) {
        result = receipts.get(hex(id));
        if (!result) report(4, 2, id);
        return;
      }
      if (opcode === 2 || opcode === 3) {
        const previous = receipts.get(hex(id));
        if (previous) {
          result = previous;
          return;
        }
        playback = opcode === 2 ? 1 : 2;
      }
      report(opcode, 0, id);
      if (opcode === 2 || opcode === 3) receipts.set(hex(id), result);
    },
  };
  const read = {
    async readValue() {
      if (!gatt.connected || !result) throw new Error("Invalid fixture read");
      if (failure === result[1]) {
        failure = null;
        throw new Error("Simulated lost app receipt");
      }
      const captured = result.slice();
      if (mismatch === captured[1]) {
        mismatch = null;
        captured[19] ^= 0xff;
      }
      if (held?.opcode === captured[1]) {
        const current = held;
        held = null;
        current.begin();
        await current.continue;
      }
      return new DataView(captured.buffer);
    },
  };
  const service = {
    async getCharacteristic(id) {
      if (id === commandId) return write;
      if (id === resultId) return read;
      throw new Error("Unexpected fixture characteristic UUID");
    },
  };
  const gatt = {
    connected: false,
    async connect() {
      gatt.connected = true;
      authorized = false;
      return gatt;
    },
    async getPrimaryService(id) {
      if (id !== serviceId) throw new Error("Unexpected fixture service UUID");
      return service;
    },
    disconnect() {
      gatt.connected = false;
      authorized = false;
      for (const listener of [...listeners]) listener();
    },
  };
  const device = {
    id: deviceId,
    gatt,
    addEventListener(name, listener) {
      if (name === "gattserverdisconnected") listeners.add(listener);
    },
    removeEventListener(name, listener) {
      if (name === "gattserverdisconnected") listeners.delete(listener);
    },
  };

  return {
    bluetooth: {
      async requestDevice(options) {
        chooserCount++;
        if (options.filters?.[0]?.services?.[0] !== serviceId) throw new Error("Missing fixture service filter");
        return device;
      },
    },
    opcodes,
    get chooserCount() { return chooserCount; },
    get connected() { return gatt.connected; },
    get lastResultCode() { return result?.[2]; },
    failReadAfter(opcode) { failure = opcode; },
    wrongResultAfter(opcode) { mismatch = opcode; },
    clearReceipts() { receipts.clear(); },
    holdReadAfter(opcode) {
      let begin;
      let release;
      const started = new Promise((resolve) => { begin = resolve; });
      const next = new Promise((resolve) => { release = resolve; });
      held = { opcode, begin, continue: next };
      return { started, release };
    },
  };
}
