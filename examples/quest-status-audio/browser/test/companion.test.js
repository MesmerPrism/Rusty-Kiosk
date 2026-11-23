import assert from "node:assert/strict";
import test from "node:test";
import { CompanionController, REQUEST_KEY } from "../controller.js";
import { Opcode, Playback, Result, encodeRequest, decodeResult, decodeHeadsetDetails, parsePairingCode } from "../protocol.js";
import { makeQuest } from "./fake-quest.js";

const code = "838485868788898a8b8c8d8e8f909192";
const otherCode = "939495969798999a9b9c9d9e9fa0a1a2";
const epoch = "1718191a1b1c1d1e1f20212223242526";

function storage() {
  const values = new Map();
  return {
    values,
    getItem(key) { return values.get(key) ?? null; },
    setItem(key, value) { values.set(key, value); },
    removeItem(key) { values.delete(key); },
  };
}

function controller(quest, journal = storage(), overrides = {}) {
  let index = 0;
  return new CompanionController({
    bluetooth: quest.bluetooth,
    storage: journal,
    randomBytes: () => new Uint8Array(16).fill(++index),
    ...overrides,
  });
}

test("protocol enforces shape, version and enum bounds", () => {
  assert.equal(parsePairingCode(` ${code.toUpperCase()} `)?.length, 16);
  assert.equal(parsePairingCode("0".repeat(32)), null);
  assert.equal(parsePairingCode("eight chars"), null);
  assert.equal(encodeRequest(Opcode.PLAY, new Uint8Array(16)).length, 18);
  assert.throws(() => encodeRequest(255, new Uint8Array(16)));
  assert.throws(() => encodeRequest(Opcode.PLAY, new Uint8Array(15)));
  assert.throws(() => decodeResult(new DataView(new ArrayBuffer(19))));
  const invalid = Uint8Array.of(2, 0, 0, 0, ...new Uint8Array(16));
  assert.throws(() => decodeResult(new DataView(invalid.buffer)));
  invalid.set([1, 0, 9, 0]);
  assert.throws(() => decodeResult(new DataView(invalid.buffer)));
});

test("headset details validates the fixed frame, unknown values and big-endian expiry", () => {
  const bytes = Uint8Array.of(1, 255, 0, 3, 132, ...parsePairingCode(epoch));
  const view = new DataView(bytes.buffer);
  assert.deepEqual(decodeHeadsetDetails(view), { battery: null, charging: 0, remainingSeconds: 900, epoch });
  assert.throws(() => decodeHeadsetDetails(new DataView(bytes.buffer, 0, 20)));
  bytes[1] = 101;
  assert.throws(() => decodeHeadsetDetails(view));
  bytes[1] = 50;
  bytes[2] = 4;
  assert.throws(() => decodeHeadsetDetails(view));
  bytes[2] = 3;
  view.setUint16(3, 901);
  assert.throws(() => decodeHeadsetDetails(view));
  view.setUint16(3, 60);
  bytes.fill(0, 5);
  assert.throws(() => decodeHeadsetDetails(view));
});

test("explicit pairing, status, Play and Stop use distinct app receipts", async () => {
  const quest = makeQuest({ code, epoch });
  const journal = storage();
  const client = controller(quest, journal);
  assert.equal(quest.chooserCount, 0);
  await client.connect("malformed");
  assert.equal(quest.chooserCount, 0);
  await client.connect(code);
  assert.equal(quest.chooserCount, 1);
  assert.equal(client.state.phase, "connected");
  assert.equal(client.state.playback, Playback.IDLE);
  assert.equal(client.state.detailsStatus, "unavailable");

  await client.play();
  assert.equal(client.state.playback, Playback.PLAYING);
  await client.stop();
  assert.equal(client.state.playback, Playback.STOPPED);
  assert.deepEqual(quest.opcodes, [Opcode.AUTH, Opcode.STATUS, Opcode.PLAY, Opcode.STOP]);
  assert.equal(journal.values.size, 0);
  client.disconnect();
  assert.equal(client.state.playback, null);
});

test("paired headset details retain a confirmation time and become stale when unreadable or disconnected", async () => {
  const details = { battery: 72, charging: 2, remainingSeconds: 90 };
  const quest = makeQuest({ code, epoch, details });
  let now = 1000;
  const client = controller(quest, storage(), { wallTime: () => now });
  await client.connect(code);
  assert.equal(client.state.detailsStatus, "current");
  assert.deepEqual(client.state.details, { ...details, confirmedAt: 1000 });
  quest.setDetails({ ...details, battery: 73, charging: 3, remainingSeconds: 61 });
  now = 2000;
  await client.refresh();
  assert.deepEqual(client.state.details, { battery: 73, charging: 3, remainingSeconds: 61, confirmedAt: 2000 });
  quest.setDetails({ fail: true });
  now = 3000;
  await client.refresh();
  assert.equal(client.state.detailsStatus, "stale");
  assert.equal(client.state.details.confirmedAt, 2000);
  await client.play();
  assert.equal(client.state.playback, Playback.PLAYING);
  client.disconnect();
  assert.equal(client.state.detailsStatus, "stale");
  assert.equal(client.state.details.battery, 73);
});

test("headset details from a different session or retired link never replace the confirmed observation", async () => {
  const details = { battery: 72, charging: 1, remainingSeconds: 90 };
  const quest = makeQuest({ code, epoch, details });
  const client = controller(quest);
  await client.connect(code);
  quest.setDetails({ ...details, battery: 100, epoch: "ff".repeat(16) });
  await client.refresh();
  assert.equal(client.state.detailsStatus, "stale");
  assert.equal(client.state.details.battery, 72);
  quest.setDetails({ ...details, battery: 73 });
  const hold = quest.holdDetailsRead();
  const refresh = client.refresh();
  await hold.started;
  client.disconnect();
  quest.setDetails({ ...details, battery: 74 });
  await client.connect(code);
  hold.release();
  await refresh;
  assert.equal(client.state.detailsStatus, "current");
  assert.equal(client.state.details.battery, 74);
});

test("elapsed session time preserves an in-flight request and prevents any new playback effect", async () => {
  const quest = makeQuest({ code, epoch, details: { battery: 72, charging: 1, remainingSeconds: 2 } });
  const journal = storage();
  let elapsed = 0;
  const client = controller(quest, journal, { elapsedTime: () => elapsed });
  await client.connect(code);
  const hold = quest.holdReadAfter(Opcode.PLAY);
  const play = client.play();
  await hold.started;
  elapsed = 1000;
  client.tick();
  assert.equal(client.state.remainingSeconds, 1);
  elapsed = 2000;
  client.tick();
  assert.equal(client.state.phase, "disconnected");
  assert.equal(client.state.hasPending, true);
  assert.match(client.state.note, /new code/i);
  assert.match(client.state.note, /unknown/i);
  hold.release();
  await play;
  await client.stop();
  assert.deepEqual(quest.opcodes, [Opcode.AUTH, Opcode.STATUS, Opcode.PLAY]);
  assert.ok(journal.getItem(REQUEST_KEY));
});

test("zero remaining session seconds requires a new code before any playback", async () => {
  const quest = makeQuest({ code, epoch, details: { battery: 72, charging: 1, remainingSeconds: 0 } });
  const client = controller(quest);
  await client.connect(code);
  assert.equal(client.state.phase, "disconnected");
  assert.match(client.state.note, /new code/i);
  await client.play();
  assert.deepEqual(quest.opcodes, [Opcode.AUTH, Opcode.STATUS]);
});

test("failed pairing retires the link and leaves no stored credential", async () => {
  const quest = makeQuest({ code, epoch });
  const journal = storage();
  const client = controller(quest, journal);
  await client.connect(otherCode);
  assert.equal(client.state.phase, "disconnected");
  assert.equal(quest.connected, false);
  assert.match(client.state.note, /pairing/i);
  assert.equal(journal.values.size, 0);
});

test("reload after an ambiguous Play looks up only its saved exact receipt", async () => {
  const quest = makeQuest({ code, epoch });
  const journal = storage();
  const oldClient = controller(quest, journal);
  await oldClient.connect(code);
  quest.failReadAfter(Opcode.PLAY);
  await oldClient.play();
  assert.equal(oldClient.state.hasPending, true);
  const saved = journal.getItem(REQUEST_KEY);
  assert.ok(saved.includes(epoch));
  assert.ok(!saved.includes(code));
  oldClient.disconnect();

  const nextClient = controller(quest, journal);
  assert.equal(nextClient.state.hasPending, true);
  assert.equal(quest.chooserCount, 1);
  await nextClient.connect(code);
  assert.equal(nextClient.state.playback, Playback.PLAYING);
  assert.equal(nextClient.state.hasPending, false);
  assert.ok(quest.opcodes.includes(Opcode.LOOKUP));
  assert.equal(quest.opcodes.filter(value => value === Opcode.PLAY).length, 1);
  assert.match(nextClient.state.note, /previous/i);
});

test("a different device or a different epoch leaves a saved operation unresolved", async () => {
  for (const scope of [{ deviceId: "other-device" }, { epoch: "ff".repeat(16) }]) {
    const originalQuest = makeQuest({ code, epoch });
    const journal = storage();
    const previous = controller(originalQuest, journal);
    await previous.connect(code);
    originalQuest.failReadAfter(Opcode.PLAY);
    await previous.play();
    previous.disconnect();

    const newQuest = makeQuest({ code, epoch, ...scope });
    const next = controller(newQuest, journal);
    await next.connect(code);
    assert.equal(next.state.hasPending, true);
    await next.stop();
    assert.deepEqual(newQuest.opcodes, [Opcode.AUTH, Opcode.STATUS]);
    next.dismissPending();
    assert.equal(next.state.hasPending, false);
    assert.equal(journal.getItem(REQUEST_KEY), null);
  }
});

test("receipt mismatch and lookup unknown never confirm a mutation", async () => {
  const quest = makeQuest({ code, epoch });
  const journal = storage();
  const client = controller(quest, journal);
  await client.connect(code);
  quest.wrongResultAfter(Opcode.PLAY);
  await client.play();
  assert.equal(client.state.hasPending, true);
  assert.match(client.state.note, /unknown/i);
  client.disconnect();
  quest.clearReceipts();
  await controller(quest, journal).connect(code);
  assert.ok(journal.getItem(REQUEST_KEY));
  assert.equal(quest.opcodes.filter(value => value === Opcode.PLAY).length, 1);
  assert.equal(quest.lastResultCode, Result.UNKNOWN);
});

test("a lost or malformed journal disables effects until deliberately cleared", async () => {
  const quest = makeQuest({ code, epoch });
  const journal = storage();
  journal.setItem(REQUEST_KEY, "{} invalid");
  const client = controller(quest, journal);
  await client.connect(code);
  assert.equal(client.state.hasPending, true);
  await client.play();
  assert.deepEqual(quest.opcodes, [Opcode.AUTH, Opcode.STATUS]);
  client.dismissPending();
  journal.setItem = () => { throw new Error("Storage failed"); };
  await client.play();
  assert.deepEqual(quest.opcodes, [Opcode.AUTH, Opcode.STATUS]);
  assert.match(client.state.note, /saved|store/i);
});

test("a late result from a retired GATT link cannot update the current view", async () => {
  const quest = makeQuest({ code, epoch });
  const client = controller(quest);
  await client.connect(code);
  const hold = quest.holdReadAfter(Opcode.PLAY);
  const play = client.play();
  await hold.started;
  client.disconnect();
  const connect = client.connect(code);
  await connect;
  assert.equal(client.state.phase, "connected");
  assert.equal(client.state.hasPending, false);
  hold.release();
  await play;
  assert.match(client.state.note, /previous/i);
  assert.equal(quest.opcodes.filter(value => value === Opcode.PLAY).length, 1);
});
