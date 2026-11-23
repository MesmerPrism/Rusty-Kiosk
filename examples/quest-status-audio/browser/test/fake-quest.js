// A real WebCrypto peer for both the pairing exchange and signed LAN requests.
export function makeQuest({ epoch, deviceId = "a1".repeat(16), details = { battery: 62, charging: 1, remainingSeconds: 90 },
  allowPairing = true, headsetApproved = true, address = "http://192.168.7.20:47876/v1/gateway" }) {
  const serviceId = "2e1fb001-2d5c-44ed-93de-9ecd76c520d6";
  const discoveryId = "2e1fb005-2d5c-44ed-93de-9ecd76c520d6";
  const encoder = new TextEncoder();
  const bytes = hex => Uint8Array.from(hex.match(/../g), pair => Number.parseInt(pair, 16));
  const hex = value => Array.from(new Uint8Array(value), byte => byte.toString(16).padStart(2, "0")).join("");
  const base64 = value => btoa(String.fromCharCode(...new Uint8Array(value))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const unbase64 = value => Uint8Array.from(atob(value.replace(/-/g, "+").replace(/_/g, "/")), char => char.charCodeAt(0));
  const hash = value => crypto.subtle.digest("SHA-256", encoder.encode(value));
  const importHmac = value => crypto.subtle.importKey("raw", value, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const hmac = async (key, value) => hex(await crypto.subtle.sign("HMAC", await key, encoder.encode(value)));
  const pairPrefix = "RQ-PHONE-PAIR-2";
  const enrollment = { controller: "2a".repeat(16), key: "3b".repeat(32) };
  const commandKey = importHmac(encoder.encode(enrollment.key));
  const owner = { device: deviceId, serial: "", identity_kind: "app-installation", epoch: "7", native_epoch: "8" };
  const metadata = () => ({ v: 2, ...owner, mode: "remote", gateways: [address], pairing: 1, enabled: true, remote_available: true });
  const receipts = new Map();
  const nonceHistory = new Set();
  const opcodes = [];
  let chooserCount = 0;
  let pairingCount = 0;
  let hasEnrollment = false;
  let pairing;
  let playback = 0;
  let lastResult;
  let failure;
  let mismatch;
  let held;
  let heldDetails;
  let badSignature = false;

  function report(opcode, status, id, mode = playback) {
    return Uint8Array.of(1, opcode, status, mode, ...bytes(id));
  }

  async function exchangePair(body) {
    if (body.device !== owner.device || body.epoch !== owner.epoch || body.native_epoch !== owner.native_epoch) {
      throw new Error("Pairing is not bound to the selected installation and session.");
    }
    if (body.action === "pair-begin") {
      if (body.client_nonce || body.client_public_key || !/^[a-f0-9]{64}$/.test(body.client_commitment)) {
        throw new Error("Pairing began without a hidden commitment.");
      }
      pairingCount++;
      const privateServer = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
      const reply = { v: 2, device: owner.device, epoch: owner.epoch, native_epoch: owner.native_epoch,
        pairing_id: "a9".repeat(16), client_commitment: body.client_commitment,
        server_public_key: base64(await crypto.subtle.exportKey("spki", privateServer.publicKey)),
        server_nonce: base64(crypto.getRandomValues(new Uint8Array(16))), expires_in_ms: 120000 };
      const context = [owner.device, owner.serial, owner.identity_kind, owner.epoch, owner.native_epoch,
        base64(encoder.encode(body.phone_name))].join("|");
      pairing = { privateServer, context, reply };
      return reply;
    }
    if (!pairing || body.pairing_id !== pairing.reply.pairing_id) throw new Error("Invalid pairing ID.");
    const { reply } = pairing;
    if (body.action === "pair-reveal") {
      const { client_nonce: nonce, client_public_key: publicKey } = body;
      const commit = hex(await hash(`${pairPrefix}/commit|${pairing.context}|${nonce}|${publicKey}`));
      if (commit !== reply.client_commitment) throw new Error("The client reveal does not match its commitment.");
      const transcript = [pairPrefix, pairing.context, reply.pairing_id, commit, nonce, reply.server_nonce,
        publicKey, reply.server_public_key].join("|");
      const peer = await crypto.subtle.importKey("spki", unbase64(publicKey),
        { name: "ECDH", namedCurve: "P-256" }, false, []);
      const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: peer }, pairing.privateServer.privateKey, 256);
      const input = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveBits"]);
      const salt = await hash(transcript);
      const derive = label => crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt,
        info: encoder.encode(`${pairPrefix}/${label}`) }, input, 256);
      const sas = new DataView(await derive("SAS")).getUint32(0, false);
      pairing.code = String(sas % 1_000_000).padStart(6, "0");
      pairing.transcript = transcript;
      pairing.proof = await importHmac(await derive("PROOF"));
      pairing.aead = await crypto.subtle.importKey("raw", await derive("AEAD"), "AES-GCM", false, ["encrypt"]);
    }
    if (!pairing.proof || await hmac(pairing.proof, `${pairing.transcript}|${body.action}`) !== body.proof) {
      throw new Error("Invalid pairing proof.");
    }
    const result = { v: 2, pairing_id: reply.pairing_id, state: "pending" };
    if (body.action === "pair-reveal") return result;
    if (body.action === "pair-cancel" || !allowPairing) return { ...result, state: "rejected" };
    if (body.action === "pair-confirm") pairing.browserApproved = true;
    if (!pairing.browserApproved || !headsetApproved) return result;
    const ticket = { v: 2, ...owner, ...enrollment };
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv,
      additionalData: encoder.encode(`${pairPrefix}/result|${pairing.transcript}`) }, pairing.aead,
    encoder.encode(JSON.stringify(ticket)));
    hasEnrollment = true;
    return { ...result, state: "approved", iv: base64(iv), data: base64(data) };
  }

  async function exchangeCommand(body) {
    const { controller, nonce, action, payload, mac } = body;
    if (!hasEnrollment || controller !== enrollment.controller || body.epoch !== owner.epoch) return new Response(null, { status: 403 });
    if (!/^[a-f0-9]{32}$/.test(nonce) || nonceHistory.has(nonce)
      || mac !== await hmac(commandKey, `RQG1|${controller}|${owner.epoch}|${nonce}|${action}|${payload}`)) {
      throw new Error("Invalid command proof or repeated nonce.");
    }
    nonceHistory.add(nonce);
    const { id, opcode } = JSON.parse(payload);
    if (!/^[a-f0-9]{32}$/.test(id)) throw new Error("Invalid request ID.");
    let result;
    let snapshot;
    if (action === "command" && (opcode === 2 || opcode === 3)) {
      opcodes.push(opcode);
      result = receipts.get(id);
      if (!result) {
        playback = opcode === 2 ? 1 : 2;
        result = report(opcode, 0, id);
        receipts.set(id, result);
      }
    } else if (action === "receipt") {
      opcodes.push(4);
      result = receipts.get(id) ?? report(4, 2, id);
    } else if (action === "status") {
      opcodes.push(1);
      result = report(1, 0, id);
      if (details && !details.fail) {
        const frame = new Uint8Array(21);
        frame.set([1, details.battery, details.charging]);
        new DataView(frame.buffer).setUint16(3, details.remainingSeconds);
        frame.set(bytes(details.epoch ?? epoch), 5);
        snapshot = hex(frame);
      }
      if (heldDetails) {
        const current = heldDetails;
        heldDetails = null;
        current.begin();
        await current.continue;
      }
    } else throw new Error("Unexpected command action.");
    lastResult = result;
    if (failure === result[1]) {
      failure = null;
      throw new Error("Simulated lost app receipt.");
    }
    const frame = result.slice();
    if (mismatch === frame[1]) {
      mismatch = null;
      frame[19] ^= 0xff;
    }
    if (held?.opcode === frame[1]) {
      const current = held;
      held = null;
      current.begin();
      await current.continue;
    }
    const resultBody = JSON.stringify({ result: hex(frame), ...(action === "status" ? { details: snapshot } : {}) });
    const signed = badSignature ? "0".repeat(64) : await hmac(commandKey,
      `RQG1-REPLY|${controller}|${owner.device}|${owner.epoch}|${nonce}|${action}|${resultBody}`);
    badSignature = false;
    return Response.json({ v: 1, device: owner.device, epoch: owner.epoch, nonce, action, payload: resultBody, mac: signed });
  }

  const service = { async getCharacteristic(id) {
    if (id !== discoveryId) throw new Error("Unexpected discovery characteristic UUID.");
    return { async readValue() { return new DataView(encoder.encode(JSON.stringify(metadata())).buffer); } };
  } };
  const gatt = {
    connected: false,
    async connect() { gatt.connected = true; return gatt; },
    async getPrimaryService(id) {
      if (id !== serviceId) throw new Error("Unexpected fixture service UUID.");
      return service;
    },
    disconnect() { gatt.connected = false; },
  };
  const device = { id: `grant-${deviceId}`, gatt };

  function hold() {
    let begin;
    let release;
    const started = new Promise(resolve => { begin = resolve; });
    const next = new Promise(resolve => { release = resolve; });
    return { started, release, fixture: { begin, continue: next } };
  }

  return {
    bluetooth: {
      async requestDevice(options) {
        chooserCount++;
        if (options.filters?.[0]?.services?.[0] !== serviceId) throw new Error("Missing fixture service filter.");
        return device;
      },
      async getDevices() { return [device]; },
    },
    async fetcher(url, init) {
      if (init.mode !== "cors" || init.targetAddressSpace !== "local" || init.credentials !== "omit"
        || init.redirect !== "error" || init.cache !== "no-store") throw new Error("Unsafe network transport options.");
      if (url !== address) throw new Error("Headset address changed.");
      if (init.method === "GET") return Response.json(metadata());
      const body = JSON.parse(init.body);
      return body.v === 2 ? Response.json(await exchangePair(body)) : exchangeCommand(body);
    },
    opcodes,
    get chooserCount() { return chooserCount; },
    get pairingCount() { return pairingCount; },
    get comparisonDigits() { return pairing?.code; },
    get browserApproved() { return pairing?.browserApproved === true; },
    get connected() { return gatt.connected; },
    get lastResultCode() { return lastResult?.[2]; },
    revokeEnrollment() { hasEnrollment = false; },
    approveOnHeadset() { headsetApproved = true; },
    setAddress(value) { address = value; },
    failReadAfter(opcode) { failure = opcode; },
    wrongResultAfter(opcode) { mismatch = opcode; },
    breakSignature() { badSignature = true; },
    clearReceipts() { receipts.clear(); },
    setDetails(value) { details = value; },
    holdDetailsRead() {
      const { started, release, fixture } = hold();
      heldDetails = fixture;
      return { started, release };
    },
    holdReadAfter(opcode) {
      const { started, release, fixture } = hold();
      held = { ...fixture, opcode };
      return { started, release };
    },
  };
}
