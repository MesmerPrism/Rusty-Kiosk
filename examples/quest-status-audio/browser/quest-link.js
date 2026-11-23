import { SERVICE_UUID, identityHex, isIdentity } from "./protocol.js";

const DISCOVERY_UUID = "2e1fb005-2d5c-44ed-93de-9ecd76c520d6";
const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });
const decimal = /^[1-9][0-9]{0,18}$/;
const hex64 = /^[0-9a-f]{64}$/;
const hexBytes = hex => Uint8Array.from(hex.match(/../g), byte => Number.parseInt(byte, 16));

export class GatewayError extends Error {
  constructor(status) {
    super(`Headset gateway rejected the request (${status}).`);
    this.status = status;
  }
}

export function endpoint(value) {
  if (typeof value !== "string") throw new Error("Missing headset network address.");
  const match = /^http:\/\/((?:[0-9]{1,3}\.){3}[0-9]{1,3}):47876\/v1\/gateway$/.exec(value);
  const parts = match?.[1].split(".").map(Number) ?? [];
  const [first, second] = parts;
  if (!(first === 10 || first === 172 && second >= 16 && second <= 31 || first === 192 && second === 168)
    || parts.some(part => part > 255) || parts.join(".") !== match[1]) {
    throw new Error("The headset must advertise a private IPv4 address on the local network.");
  }
  return value;
}

function epoch(value) {
  return typeof value === "string" && decimal.test(value) && BigInt(value) <= 9223372036854775807n;
}

function discovery(raw) {
  if (raw?.v !== 2 || raw.identity_kind !== "app-installation" || !isIdentity(raw.device) || raw.serial !== ""
    || !epoch(raw.epoch) || !epoch(raw.native_epoch) || raw.pairing !== 1 || raw.mode !== "remote"
    || raw.enabled !== true || raw.remote_available !== true || !Array.isArray(raw.gateways)
    || raw.gateways.length !== 1) throw new Error("The headset has no active companion session to connect to.");
  const owner = { device: raw.device, serial: raw.serial, identity_kind: raw.identity_kind,
    epoch: raw.epoch, native_epoch: raw.native_epoch };
  return { owner, endpoint: endpoint(raw.gateways[0]) };
}

async function until(operation, signal) {
  signal.throwIfAborted();
  let cancel;
  const aborted = new Promise((_, reject) => {
    cancel = () => reject(signal.reason);
    signal.addEventListener("abort", cancel, { once: true });
  });
  try { return await Promise.race([operation, aborted]); }
  finally { signal.removeEventListener("abort", cancel); }
}

export async function discoverHeadset(device, lifetime) {
  if (!device?.gatt || typeof device.id !== "string" || !device.id || device.id.length > 256) {
    throw new Error("The browser returned an invalid Bluetooth device.");
  }
  const signal = AbortSignal.any([lifetime, AbortSignal.timeout(12_000)]);
  try {
    const connection = device.gatt.connect().then(gatt => {
      if (signal.aborted) gatt.disconnect();
      signal.throwIfAborted();
      return gatt;
    });
    const gatt = await until(connection, signal);
    const service = await until(gatt.getPrimaryService(SERVICE_UUID), signal);
    const info = await until(service.getCharacteristic(DISCOVERY_UUID), signal);
    const response = await until(info.readValue(), signal);
    if (response.byteLength > 512) throw new Error("Headset discovery response is too large.");
    return discovery(JSON.parse(decoder.decode(response)));
  } finally {
    if (device.gatt.connected) device.gatt.disconnect();
  }
}

export function validEnrollment(value) {
  return isIdentity(value?.controller) && typeof value.key === "string" && hex64.test(value.key);
}

function gatewayRequest(address, fetcher) {
  return (body, signal) => fetcher(address, {
    method: body === undefined ? "GET" : "POST",
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    mode: "cors", cache: "no-store", credentials: "omit", redirect: "error",
    referrerPolicy: "no-referrer", targetAddressSpace: "local", signal,
  });
}

async function readJson(response) {
  if (!response.ok) throw new GatewayError(response.status);
  const reader = response.body?.getReader();
  if (!reader) throw new Error("The headset returned an empty response.");
  const buffer = new Uint8Array(8192);
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return JSON.parse(decoder.decode(buffer.subarray(0, size)));
      if (size + value.byteLength > buffer.length) throw new Error("Headset response is too large.");
      buffer.set(value, size);
      size += value.byteLength;
    }
  } finally { await reader.cancel().catch(() => {}); }
}

export async function openGateway(address, { installation, seen, signal, fetcher }) {
  const request = gatewayRequest(endpoint(address), fetcher);
  const fetchJson = async body => {
    const requestSignal = AbortSignal.any([signal, AbortSignal.timeout(6500)]);
    const result = await readJson(await request(body, requestSignal));
    requestSignal.throwIfAborted();
    return result;
  };
  const { owner, endpoint: advertised } = discovery(await fetchJson());
  if (owner.device !== installation || advertised !== address || (seen &&
    (owner.epoch !== seen.epoch || owner.native_epoch !== seen.native_epoch))) {
    throw new Error("The network address does not belong to the selected headset session.");
  }
  return {
    owner,
    pairing: { request },
    async authenticate(enrollment) {
      if (!validEnrollment(enrollment)) throw new Error("Invalid saved headset enrollment.");
      const { controller, key } = enrollment;
      const secret = await crypto.subtle.importKey("raw", encoder.encode(key),
        { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
      return async (action, body) => {
        signal.throwIfAborted();
        const nonce = identityHex(crypto.getRandomValues(new Uint8Array(16)));
        const payload = JSON.stringify(body);
        const mac = identityHex(new Uint8Array(await crypto.subtle.sign("HMAC", secret,
          encoder.encode(`RQG1|${controller}|${owner.epoch}|${nonce}|${action}|${payload}`))));
        const reply = await fetchJson({ v: 1, controller, epoch: owner.epoch, nonce, action, payload, mac });
        if (reply?.v !== 1 || reply.device !== owner.device || reply.epoch !== owner.epoch || reply.nonce !== nonce
          || reply.action !== action || typeof reply.payload !== "string" || !hex64.test(reply.mac)
          || !await crypto.subtle.verify("HMAC", secret, hexBytes(reply.mac), encoder.encode(
            `RQG1-REPLY|${controller}|${owner.device}|${owner.epoch}|${nonce}|${action}|${reply.payload}`))) {
          throw new Error("The headset response could not be authenticated.");
        }
        signal.throwIfAborted();
        return JSON.parse(reply.payload);
      };
    },
  };
}
