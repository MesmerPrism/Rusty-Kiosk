import { CompanionController } from "./controller.js";
import { renderPageQr, shareablePageUrl } from "./page-qr.js";

const PLAYBACK_LABELS = ["Idle", "Playing", "Stopped", "Completed", "Unavailable"];
const CONNECTION_LABELS = { disconnected: "Disconnected", connecting: "Connecting…", connected: "Connected" };
const CONNECTION_STAGES = { discovering: "Find a headset", network: "Opening local connection…",
  pairing: "Confirm pairing", reconnecting: "Reconnecting…", authenticating: "Checking headset access…" };
const CHARGING_LABELS = ["Unknown", "On battery", "Charging", "Full"];
const DETAILS_LABELS = { unavailable: "Unavailable", current: "Confirmed", stale: "Last known" };
const clockLabel = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit", second: "2-digit" });

function setText(element, value) {
  if (element.textContent !== value) element.textContent = value;
}

function remainingLabel(seconds) {
  if (seconds == null) return "—";
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, "0")}`;
}

function browserStorage(name) {
  try {
    return globalThis[name];
  } catch {
    return null;
  }
}

export function mount({ document, bluetooth, storage, enrollmentStorage }) {
  const pair = document.getElementById("pair");
  const approval = document.getElementById("pair-approval");
  const comparison = document.getElementById("pairing-code");
  const approve = document.getElementById("pair-confirm");
  const reject = document.getElementById("pair-reject");
  const reconnect = document.getElementById("reconnect");
  const forget = document.getElementById("forget");
  const connection = document.getElementById("connection");
  const disconnect = document.getElementById("disconnect");
  const playback = document.getElementById("playback");
  const play = document.getElementById("play");
  const stop = document.getElementById("stop");
  const refresh = document.getElementById("refresh");
  const note = document.getElementById("note");
  const dismiss = document.getElementById("dismiss");
  const detailsStatus = document.getElementById("details-status");
  const battery = document.getElementById("battery");
  const charging = document.getElementById("charging");
  const remaining = document.getElementById("remaining");
  const confirmedReading = document.getElementById("confirmed-reading");
  const confirmedTime = document.getElementById("confirmed-time");

  function render(state) {
    const connected = state.phase === "connected";
    const connecting = state.phase === "connecting";
    const showApproval = Boolean(state.pairingCode);
    const focusApproval = showApproval && approval.hidden;
    pair.hidden = connected || connecting;
    pair.disabled = !bluetooth?.requestDevice;
    reconnect.hidden = connected || connecting || !state.remembered;
    forget.hidden = connecting || !state.remembered;
    approval.hidden = !showApproval;
    setText(comparison, state.pairingCode ?? "");
    setText(connection, state.sessionExpired ? "Session expired" : CONNECTION_STAGES[state.stage] ?? CONNECTION_LABELS[state.phase]);
    connection.dataset.phase = state.phase;
    disconnect.hidden = !connected && !connecting;
    setText(disconnect, connecting ? "Cancel connection" : "Disconnect");
    setText(playback, PLAYBACK_LABELS[state.playback] ?? "Unavailable");
    setText(detailsStatus, DETAILS_LABELS[state.detailsStatus]);
    setText(battery, state.details?.battery == null ? "—" : `${state.details.battery}%`);
    setText(charging, CHARGING_LABELS[state.details?.charging] ?? "—");
    setText(remaining, remainingLabel(state.detailsStatus === "current" ? state.remainingSeconds : null));
    confirmedReading.hidden = !state.details;
    if (state.details) {
      const confirmedAt = new Date(state.details.confirmedAt);
      confirmedTime.dateTime = confirmedAt.toISOString();
      setText(confirmedTime, clockLabel.format(confirmedAt));
    }
    play.disabled = !connected || state.busy || state.hasPending;
    stop.disabled = !connected || state.busy || state.hasPending;
    refresh.disabled = !connected || state.busy;
    const message = state.note || (bluetooth?.requestDevice || state.remembered ? "" :
      "Web Bluetooth is unavailable. Open this page in a supported browser over HTTPS to pair.");
    setText(note, message);
    dismiss.hidden = !state.hasPending;
    dismiss.disabled = state.busy;
    if (focusApproval) approve.focus({ preventScroll: true });
  }

  const controller = new CompanionController({ bluetooth, storage, enrollmentStorage, onChange: render });
  render(controller.state);

  pair.addEventListener("click", () => void controller.connect());
  reconnect.addEventListener("click", () => void controller.resumeSaved());
  forget.addEventListener("click", () => controller.forgetHeadset());
  approve.addEventListener("click", () => controller.answerPairing(true));
  reject.addEventListener("click", () => controller.answerPairing(false));
  disconnect.addEventListener("click", () => {
    controller.disconnect();
    (reconnect.hidden ? pair : reconnect).focus();
  });
  play.addEventListener("click", () => void controller.play());
  stop.addEventListener("click", () => void controller.stop());
  refresh.addEventListener("click", () => void controller.refresh());
  dismiss.addEventListener("click", () => controller.dismissPending());
  return controller;
}

function mountGuide() {
  const guide = document.getElementById("facilitator-guide");
  const canvas = document.getElementById("guide-qr");
  const link = document.getElementById("guide-link");
  const note = document.getElementById("guide-note");
  guide.addEventListener("toggle", () => {
    if (!guide.open) return;
    const pageUrl = shareablePageUrl(location.href);
    const fallback = new URL("./", location.href);
    fallback.username = "";
    fallback.password = "";
    link.href = pageUrl ?? fallback.href;
    canvas.hidden = true;
    if (!pageUrl) {
      setText(note, "Use an HTTPS address reachable from the phone to open the page there.");
      return;
    }
    try {
      canvas.hidden = !renderPageQr(canvas, pageUrl);
    } catch {
      // Copy and open still work when the browser cannot draw the QR.
    }
    setText(note, canvas.hidden ? "Use the companion link when the QR code is unavailable." : "");
  });
  document.getElementById("copy-guide-link").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(link.href);
      setText(note, "Companion link copied.");
    } catch {
      setText(note, "Open the companion page and copy its address.");
    }
  });
}

const controller = mount({ document, bluetooth: navigator.bluetooth, storage: browserStorage("sessionStorage"),
  enrollmentStorage: browserStorage("localStorage") });
mountGuide();
const checkVisibleConnection = (immediate = false) => {
  if (document.hidden) return;
  if (controller.state.phase === "connected") void controller.probeStatus();
  else void controller.reconnect({ immediate });
};
globalThis.setInterval(() => {
  controller.tick();
  checkVisibleConnection();
}, 1000);
globalThis.addEventListener("pagehide", () => controller.suspend());
globalThis.addEventListener("pageshow", () => checkVisibleConnection(true));
globalThis.addEventListener("online", () => checkVisibleConnection(true));
document.addEventListener("visibilitychange", () => checkVisibleConnection(true));
checkVisibleConnection(true);

if ("serviceWorker" in navigator && globalThis.isSecureContext) {
  navigator.serviceWorker.register("./sw.js").catch(() => {
    // Private browsing and host policy may disable installation. Bluetooth can still work.
  });
}
