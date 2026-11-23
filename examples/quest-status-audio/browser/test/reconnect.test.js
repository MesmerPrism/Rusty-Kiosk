import assert from "node:assert/strict";
import test from "node:test";
import { CompanionController } from "../controller.js";
import { Opcode } from "../protocol.js";
import { makeQuest } from "./fake-quest.js";

const epoch = "37".repeat(16);

function memoryStorage() {
  const entries = new Map();
  return {
    getItem: key => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, value),
    removeItem: key => entries.delete(key),
  };
}

test("pairing confirms matching digits and a new browser instance uses the saved LAN route", async () => {
  const quest = makeQuest({ epoch });
  const enrollmentStorage = memoryStorage();
  let confirmed = false;
  const original = new CompanionController({ bluetooth: quest.bluetooth, fetcher: quest.fetcher,
    storage: memoryStorage(), enrollmentStorage, onChange: state => {
      if (!state.pairingCode || confirmed) return;
      assert.match(state.pairingCode, /^\d{6}$/);
      assert.equal(state.pairingCode, quest.comparisonDigits);
      confirmed = true;
      original.answerPairing(true);
    } });
  await original.connect();
  assert.equal(confirmed, true);
  assert.equal(original.state.phase, "connected");
  assert.equal(original.state.note, "");
  assert.equal(quest.chooserCount, 1);
  original.disconnect();

  const fresh = new CompanionController({ bluetooth: undefined, fetcher: quest.fetcher,
    storage: memoryStorage(), enrollmentStorage });
  await fresh.reconnect();
  assert.equal(fresh.state.phase, "connected");
  assert.equal(quest.chooserCount, 1);
  assert.equal(quest.pairingCount, 1);
});

function controller(quest, enrollmentStorage, bluetooth = quest.bluetooth) {
  const client = new CompanionController({ bluetooth, fetcher: quest.fetcher, storage: memoryStorage(),
    enrollmentStorage, onChange: state => { if (state.pairingCode) client.answerPairing(true); } });
  return client;
}

test("browser approval alone sends no status or audio and stores no enrollment", async () => {
  const quest = makeQuest({ epoch, headsetApproved: false });
  const enrollments = memoryStorage();
  const client = controller(quest, enrollments);
  const pairing = client.connect();
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { clearInterval(interval); reject(new Error("Browser approval never arrived")); }, 2500);
    const interval = setInterval(() => {
      if (!quest.browserApproved) return;
      clearTimeout(timeout);
      clearInterval(interval);
      resolve();
    }, 20);
  });
  assert.equal(client.state.phase, "connecting");
  assert.equal(client.state.remembered, false);
  await client.play();
  assert.deepEqual(quest.opcodes, []);

  quest.approveOnHeadset();
  await pairing;
  assert.equal(client.state.phase, "connected");
  assert.equal(client.state.remembered, true);
});

test("a changed address uses a saved Bluetooth grant or explicit reselection without re-enrollment", async () => {
  const quest = makeQuest({ epoch });
  const enrollments = memoryStorage();
  const original = controller(quest, enrollments);
  await original.connect();
  original.disconnect();
  quest.setAddress("http://192.168.7.21:47876/v1/gateway");

  const automatic = controller(quest, enrollments);
  await automatic.reconnect();
  assert.equal(automatic.state.phase, "connected");
  assert.equal(quest.chooserCount, 1);
  assert.equal(quest.connected, false);
  automatic.disconnect();

  quest.setAddress("http://192.168.7.22:47876/v1/gateway");
  const explicit = controller(quest, enrollments, { requestDevice: quest.bluetooth.requestDevice });
  await explicit.reconnect();
  assert.equal(explicit.state.phase, "disconnected");
  await explicit.connect();
  assert.equal(explicit.state.phase, "connected");
  assert.equal(quest.chooserCount, 2);
  assert.equal(quest.pairingCount, 1);
});

test("signed status is required on reconnect and a revoked enrollment never triggers automatic pairing", async () => {
  const quest = makeQuest({ epoch });
  const enrollments = memoryStorage();
  const original = controller(quest, enrollments);
  await original.connect();
  original.disconnect();

  const automatic = controller(quest, enrollments, {});
  quest.breakSignature();
  await automatic.reconnect();
  assert.equal(automatic.state.phase, "disconnected");
  assert.equal(automatic.state.details, null);
  await automatic.play();
  assert.ok(!quest.opcodes.includes(Opcode.PLAY));

  quest.revokeEnrollment();
  await automatic.resumeSaved();
  assert.equal(automatic.state.phase, "disconnected");
  assert.match(automatic.state.note, /enrollment was rejected/i);
  assert.equal(quest.pairingCount, 1);
  automatic.forgetHeadset();
  assert.equal(automatic.state.remembered, false);
  await controller(quest, enrollments, {}).reconnect();
  assert.equal(quest.chooserCount, 1);
});

test("foreground status detects a lost connection and recovers with saved enrollment without replaying audio", async () => {
  const quest = makeQuest({ epoch });
  let elapsed = 0;
  let reachable = true;
  const client = new CompanionController({ bluetooth: quest.bluetooth, storage: memoryStorage(),
    enrollmentStorage: memoryStorage(), elapsedTime: () => elapsed,
    fetcher: (...args) => {
      if (!reachable) throw new TypeError("Simulated network outage");
      return quest.fetcher(...args);
    }, onChange: state => { if (state.pairingCode) client.answerPairing(true); } });
  await client.connect();
  assert.deepEqual(quest.opcodes, [Opcode.STATUS]);
  elapsed = 7999;
  await client.probeStatus();
  assert.deepEqual(quest.opcodes, [Opcode.STATUS]);

  const held = quest.holdReadAfter(Opcode.PLAY);
  const play = client.play();
  await held.started;
  elapsed = 8000;
  await client.probeStatus();
  assert.deepEqual(quest.opcodes, [Opcode.STATUS, Opcode.PLAY]);
  held.release();
  await play;

  reachable = false;
  await client.probeStatus();
  assert.equal(client.state.phase, "disconnected");
  assert.equal(client.state.detailsStatus, "stale");
  assert.equal(client.state.hasPending, false);
  reachable = true;
  await client.reconnect();
  assert.equal(client.state.phase, "disconnected");
  elapsed += 8000;
  await client.reconnect();
  assert.equal(client.state.phase, "connected");
  assert.equal(client.state.detailsStatus, "current");
  assert.equal(quest.chooserCount, 1);
  assert.equal(quest.pairingCount, 1);
  assert.deepEqual(quest.opcodes, [Opcode.STATUS, Opcode.PLAY, Opcode.STATUS]);
});
