// Adapted pairing client. See quest-pairing.source.json and quest-pairing.LICENSE.
const encode = new TextEncoder();
const prefix = 'RQ-PHONE-PAIR-2';
const decode = new TextDecoder('utf-8', { fatal: true });
const hex = bytes => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
const base64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const fail = () => { throw Error('The secure pairing exchange could not be verified.'); };
function bytes(value, max) {
  if (typeof value !== 'string' || value.length > max || !/^[A-Za-z0-9_-]+$/.test(value)) fail();
  const result = Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
  if (base64(result) !== value) fail();
  return result;
}
function keys(value, fields) {
  if (!value || Array.isArray(value) || Object.keys(value).length !== fields.length
    || fields.some(field => !Object.hasOwn(value, field))) fail();
}
const context = begin => [begin.device, begin.serial, begin.identity_kind, begin.epoch, begin.native_epoch,
  base64(encode.encode(begin.phone_name))].join('|');
export async function pairingKeys(privateKey, begin, reply, opening) {
  const transcript = [prefix, context(begin), reply.pairing_id, begin.client_commitment,
    opening.client_nonce, reply.server_nonce, opening.client_public_key, reply.server_public_key].join('|');
  const der = bytes(reply.server_public_key, 122);
  if (der.length !== 91 || hex(der.subarray(0, 27)) !== '3059301306072a8648ce3d020106082a8648ce3d03010703420004') fail();
  const publicKey = await crypto.subtle.importKey('spki', der,
    { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const secret = await crypto.subtle.deriveBits({ name: 'ECDH', public: publicKey }, privateKey, 256);
  const input = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveBits']);
  const salt = await crypto.subtle.digest('SHA-256', encode.encode(transcript));
  const derive = label => crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt,
    info: encode.encode(prefix + '/' + label) }, input, 256);
  const sas = new DataView(await derive('SAS')).getUint32(0, false);
  return { transcript, code: String(sas % 1000000).padStart(6, '0'),
    proofKey: await crypto.subtle.importKey('raw', await derive('PROOF'), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']),
    aeadKey: await crypto.subtle.importKey('raw', await derive('AEAD'), 'AES-GCM', false, ['decrypt']) };
}
const delay = (ms, signal) => new Promise((resolve, reject) => {
  signal.throwIfAborted();
  const abort = () => { clearTimeout(timer); reject(signal.reason); };
  const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, ms);
  signal.addEventListener('abort', abort, { once: true });
});

export async function pairPhone({ owner, signal, onCode, phoneName = 'Browser', wait = delay, transport }) {
  if (typeof transport?.request !== 'function') fail();
  const lifetime = AbortSignal.any([AbortSignal.timeout(120000), ...[signal].filter(Boolean)]);
  lifetime.throwIfAborted();
  if (owner?.identity_kind !== 'app-installation' || typeof onCode !== 'function'
    || !/^[a-f0-9]{32}$/.test(owner.device) || typeof owner.serial !== 'string'
    || !/^(?:[A-Za-z0-9][A-Za-z0-9._-]{0,63})?$/.test(owner.serial)
    || !['epoch', 'native_epoch'].every(field => typeof owner[field] === 'string' && /^[1-9][0-9]{0,18}$/.test(owner[field])
      && BigInt(owner[field]) <= 9223372036854775807n)) fail();
  owner = Object.freeze(Object.fromEntries(['device', 'serial', 'identity_kind', 'epoch', 'native_epoch'].map(field => [field, owner[field]])));
  if (typeof phoneName !== 'string' || !phoneName || phoneName !== phoneName.trim() || phoneName.length > 64
    || encode.encode(phoneName).length > 128 || /[\x00-\x1f\x7f-\x9f\p{Cf}]/u.test(phoneName)) fail();
  for (const char of phoneName) if (char.codePointAt(0) >= 0xd800 && char.codePointAt(0) <= 0xdfff) fail();
  const exchange = async (body, selectedSignal = lifetime) => {
    const requestLifetime = AbortSignal.any([selectedSignal, AbortSignal.timeout(6500)]);
    requestLifetime.throwIfAborted();
    const response = await transport.request(body, requestLifetime);
    requestLifetime.throwIfAborted();
    if (!response.ok) throw Error('The headset could not accept this pairing request.');
    const reader = response.body?.getReader(); if (!reader) fail();
    const buffer = new Uint8Array(8192); let length = 0;
    try {
      for (;;) {
        const { value, done } = await reader.read(); if (done) break;
        if (length + value.byteLength > buffer.length) fail();
        buffer.set(value, length); length += value.byteLength;
      }
      requestLifetime.throwIfAborted();
      return JSON.parse(decode.decode(buffer.subarray(0, length)));
    } finally { await reader.cancel().catch(() => {}); }
  };
  let reply, material, opening, complete = false;
  const request = async (action, selectedSignal) => {
    const proof = hex(new Uint8Array(await crypto.subtle.sign('HMAC', material.proofKey,
      encode.encode(material.transcript + '|' + action))));
    return exchange({ v: 2, action, device: owner.device, epoch: owner.epoch,
      native_epoch: owner.native_epoch, pairing_id: reply.pairing_id,
      ...(action === 'pair-reveal' ? opening : {}), proof }, selectedSignal);
  };
  try {
    const pair = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    // Hide BOTH initiator contributions until the responder freezes its reply.
    // This commitment ordering prevents adaptive offline SAS grinding (RFC 6189
    // sections 4.4.1.1 and 7); it is not a ZRTP implementation.
    opening = Object.freeze({
      client_public_key: base64(await crypto.subtle.exportKey('spki', pair.publicKey)),
      client_nonce: base64(crypto.getRandomValues(new Uint8Array(16))) });
    const begin = { v: 2, action: 'pair-begin', ...owner, phone_name: phoneName };
    begin.client_commitment = hex(new Uint8Array(await crypto.subtle.digest('SHA-256',
      encode.encode(prefix + '/commit|' + context(begin) + '|' + opening.client_nonce + '|' + opening.client_public_key))));
    Object.freeze(begin);
    lifetime.throwIfAborted();
    reply = await exchange(begin);
    keys(reply, ['v', 'device', 'epoch', 'native_epoch', 'pairing_id', 'client_commitment', 'server_public_key', 'server_nonce', 'expires_in_ms']);
    if (reply.v !== 2 || reply.client_commitment !== begin.client_commitment
      || !['device', 'epoch', 'native_epoch'].every(field => reply[field] === owner[field])
      || typeof reply.pairing_id !== 'string' || !/^[a-f0-9]{32}$/.test(reply.pairing_id)
      || !Number.isSafeInteger(reply.expires_in_ms) || reply.expires_in_ms < 1 || reply.expires_in_ms > 120000) fail();
    if (bytes(reply.server_nonce, 22).length !== 16) fail();
    Object.freeze(reply);
    material = await pairingKeys(pair.privateKey, begin, reply, opening);
    const deadline = performance.now() + reply.expires_in_ms;
    const revealed = await request('pair-reveal');
    keys(revealed, ['v', 'pairing_id', 'state']);
    if (revealed.v !== 2 || revealed.pairing_id !== reply.pairing_id || revealed.state !== 'pending') fail();
    lifetime.throwIfAborted();
    let consent, choiceError, confirmed = false;
    Promise.resolve().then(() => onCode({ code: material.code, signal: lifetime, pairingId: reply.pairing_id }))
      .then(value => { consent = value === true; }, error => { choiceError = error; });
    for (;;) {
      lifetime.throwIfAborted();
      if (performance.now() >= deadline) throw Error('The pairing request expired.');
      if (choiceError) throw choiceError;
      if (consent === false) throw Error('Pairing canceled.');
      const action = consent === true && !confirmed ? 'pair-confirm' : 'pair-status';
      const response = await request(action);
      if (action === 'pair-confirm') confirmed = true;
      if (response?.v !== 2 || response.pairing_id !== reply.pairing_id) fail();
      if (response.state === 'approved') {
        keys(response, ['v', 'pairing_id', 'state', 'iv', 'data']);
        if (!confirmed || consent !== true) fail();
        const iv = bytes(response.iv, 16); if (iv.length !== 12) fail();
        const clear = await crypto.subtle.decrypt({ name: 'AES-GCM', iv,
          additionalData: encode.encode(prefix + '/result|' + material.transcript) }, material.aeadKey, bytes(response.data, 4096));
        const ticket = JSON.parse(decode.decode(clear));
        keys(ticket, ['v', 'controller', 'key', 'device', 'serial', 'identity_kind', 'epoch', 'native_epoch']);
        if (ticket.v !== 2 || !['device', 'serial', 'identity_kind', 'epoch', 'native_epoch'].every(field => ticket[field] === owner[field])
          || typeof ticket.controller !== 'string' || !/^[a-f0-9]{32}$/.test(ticket.controller)
          || typeof ticket.key !== 'string' || !/^[a-f0-9]{64}$/.test(ticket.key)) fail();
        lifetime.throwIfAborted();
        complete = true;
        return ticket;
      }
      keys(response, ['v', 'pairing_id', 'state']);
      if (response.state !== 'pending') throw Error(response.state === 'rejected'
        ? 'Pairing was declined in the headset.' : 'The pairing request ended.');
      await wait(800, lifetime);
    }
  } finally {
    if (!complete && material && reply) {
      await request('pair-cancel', AbortSignal.timeout(1500)).catch(() => {});
    }
  }
}
