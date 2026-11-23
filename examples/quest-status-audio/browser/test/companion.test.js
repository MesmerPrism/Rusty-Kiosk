import assert from "node:assert/strict";
import test from "node:test";
import { CompanionController, REQUEST_KEY } from "../controller.js";
import { Opcode, Playback, Result, encodeRequest, decodeResult, parsePairingCode } from "../protocol.js";
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

  await client.play();
  assert.equal(client.state.playback, Playback.PLAYING);
  await client.stop();
  assert.equal(client.state.playback, Playback.STOPPED);
  assert.deepEqual(quest.opcodes, [Opcode.AUTH, Opcode.STATUS, Opcode.PLAY, Opcode.STOP]);
  assert.equal(journal.values.size, 0);
  client.disconnect();
  assert.equal(client.state.playback, null);
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
