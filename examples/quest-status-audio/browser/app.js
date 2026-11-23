import { CompanionController } from "./controller.js";

const PLAYBACK_LABELS = ["Idle", "Playing", "Stopped", "Completed", "Unavailable"];
const CONNECTION_LABELS = { disconnected: "Disconnected", connecting: "Connecting…", connected: "Connected" };

function browserStorage() {
  try {
    return globalThis.sessionStorage;
  } catch {
    return null;
  }
}

export function mount({ document, bluetooth, storage }) {
  const form = document.getElementById("pair-form");
  const input = document.getElementById("pairing-code");
  const pair = document.getElementById("pair");
  const connection = document.getElementById("connection");
  const disconnect = document.getElementById("disconnect");
  const playback = document.getElementById("playback");
  const play = document.getElementById("play");
  const stop = document.getElementById("stop");
  const refresh = document.getElementById("refresh");
  const note = document.getElementById("note");
  const dismiss = document.getElementById("dismiss");

  function render(state) {
    const connected = state.phase === "connected";
    const connecting = state.phase === "connecting";
    form.hidden = connected;
    input.disabled = connecting || !bluetooth;
    pair.disabled = connecting || !bluetooth;
    connection.textContent = CONNECTION_LABELS[state.phase];
    connection.dataset.phase = state.phase;
    disconnect.hidden = !connected;
    playback.textContent = PLAYBACK_LABELS[state.playback] ?? "Unavailable";
    play.disabled = !connected || state.busy || state.hasPending;
    stop.disabled = !connected || state.busy || state.hasPending;
    refresh.disabled = !connected || state.busy;
    const message = state.note || (bluetooth ? "" : "Web Bluetooth is unavailable. Open this page in a supported browser over HTTPS.");
    if (note.textContent !== message) note.textContent = message;
    dismiss.hidden = !state.hasPending;
    dismiss.disabled = state.busy;
  }

  const controller = new CompanionController({ bluetooth, storage, onChange: render });
  render(controller.state);

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const code = input.value;
    input.value = "";
    void controller.connect(code);
  });
  disconnect.addEventListener("click", () => {
    controller.disconnect();
    input.focus();
  });
  play.addEventListener("click", () => void controller.play());
  stop.addEventListener("click", () => void controller.stop());
  refresh.addEventListener("click", () => void controller.refresh());
  dismiss.addEventListener("click", () => controller.dismissPending());
  return controller;
}

const controller = mount({ document, bluetooth: navigator.bluetooth, storage: browserStorage() });
globalThis.addEventListener("pagehide", () => controller.disconnect());

if ("serviceWorker" in navigator && globalThis.isSecureContext) {
  navigator.serviceWorker.register("./sw.js").catch(() => {
    // Private browsing and host policy may disable installation. Bluetooth can still work.
  });
}
