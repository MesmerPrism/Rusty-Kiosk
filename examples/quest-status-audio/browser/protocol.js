export const SERVICE_UUID = "2e1fb001-2d5c-44ed-93de-9ecd76c520d6";
export const COMMAND_UUID = "2e1fb002-2d5c-44ed-93de-9ecd76c520d6";
export const RESULT_UUID = "2e1fb003-2d5c-44ed-93de-9ecd76c520d6";
export const DETAILS_UUID = "2e1fb004-2d5c-44ed-93de-9ecd76c520d6";

export const Opcode = Object.freeze({ AUTH: 0, STATUS: 1, PLAY: 2, STOP: 3, LOOKUP: 4 });
export const Result = Object.freeze({ OK: 0, REJECTED: 1, UNKNOWN: 2, FAILED: 3 });
export const Playback = Object.freeze({ IDLE: 0, PLAYING: 1, STOPPED: 2, COMPLETED: 3, UNAVAILABLE: 4 });
export const Charging = Object.freeze({ UNKNOWN: 0, ON_BATTERY: 1, CHARGING: 2, FULL: 3 });

const VERSION = 1;
const ID_LENGTH = 16;
const KNOWN_OPCODES = new Set(Object.values(Opcode));
const KNOWN_RESULTS = new Set(Object.values(Result));
const KNOWN_PLAYBACK = new Set(Object.values(Playback));
const KNOWN_CHARGING = new Set(Object.values(Charging));

export function isIdentity(value) {
  return typeof value === "string" && /^[a-f0-9]{32}$/.test(value) && value !== "0".repeat(32);
}

export function identityBytes(value) {
  if (!isIdentity(value)) throw new Error("Invalid identity");
  return Uint8Array.from(value.match(/../g), (pair) => Number.parseInt(pair, 16));
}

export function identityHex(bytes) {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function parsePairingCode(value) {
  const hex = typeof value === "string" ? value.trim().toLowerCase() : "";
  return isIdentity(hex) ? identityBytes(hex) : null;
}

export function encodeRequest(opcode, id) {
  if (!KNOWN_OPCODES.has(opcode) || !(id instanceof Uint8Array) || id.length !== ID_LENGTH) {
    throw new Error("Invalid request frame");
  }
  return Uint8Array.of(VERSION, opcode, ...id);
}

export function decodeResult(view) {
  if (!(view instanceof DataView) || view.byteLength !== ID_LENGTH + 4 || view.getUint8(0) !== VERSION) {
    throw new Error("Invalid result frame");
  }
  const opcode = view.getUint8(1);
  const result = view.getUint8(2);
  const playback = view.getUint8(3);
  if (!KNOWN_OPCODES.has(opcode) || !KNOWN_RESULTS.has(result) || !KNOWN_PLAYBACK.has(playback)) {
    throw new Error("Unsupported result value");
  }
  return { opcode, result, playback, id: identityHex(new Uint8Array(view.buffer, view.byteOffset + 4, ID_LENGTH)) };
}

export function decodeHeadsetDetails(view) {
  if (!(view instanceof DataView) || view.byteLength !== ID_LENGTH + 5 || view.getUint8(0) !== VERSION) {
    throw new Error("Invalid headset details frame");
  }
  const battery = view.getUint8(1);
  const charging = view.getUint8(2);
  const remainingSeconds = view.getUint16(3);
  const epoch = identityHex(new Uint8Array(view.buffer, view.byteOffset + 5, ID_LENGTH));
  if ((battery > 100 && battery !== 255) || !KNOWN_CHARGING.has(charging)
      || remainingSeconds > 900 || !isIdentity(epoch)) {
    throw new Error("Unsupported headset details value");
  }
  return { battery: battery === 255 ? null : battery, charging, remainingSeconds, epoch };
}
