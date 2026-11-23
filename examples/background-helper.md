# Composing a Quest background helper

Use this walkthrough to choose and test existing helpers. The combined browser,
companion and shell-worker sequence is a reference design. Each step cites its
own evidence. Choose a package using the [distribution guide](../docs/UTILITY_DISTRIBUTION.md).

## 1. Connect the operator

- **Browser and Quest on the same network.** Start with an ordinary Quest
  companion. Use BLE for discovery on supported browsers, then open a paired,
  authenticated LAN endpoint. Provide an explicit LAN pairing route for browsers
  without BLE. Browser local-network permission, app enrollment, and command
  confirmation are separate checks. Keep the endpoint scoped to app-owned
  actions and encrypt production traffic. The current Hub's LAN WebSocket is
  a trusted-network experiment. Pairing authenticates that connection, and
  TLS/WSS remains a production requirement.
- **Desktop provisioning and Kiosk control.** QFM installs the selected Kiosk and
  setup APK pair over authorized USB. Use the wearer-enabled Kiosk Direct Link
  for routine controls after provisioning. Its original control routes and the
  separate encrypted catalogue summary have different coverage. QFM's optional
  browser UI uses its own loopback connector on the host running the browser.
- **Browser USB or Wi-Fi debugging.** QFM's read-only WebUSB proof requires a
  supported browser, available USB interface and headset authorization. For
  raw Wi-Fi ADB, a normal PWA needs an intermediary that can reach the headset.
  A browser can connect directly to a Quest app's typed LAN API.

[Quest BLE browser client](https://github.com/MesmerPrism/rusty-quest/blob/fb5781031a51bd0709a5941e0a271360b8db49d7/docs/BLE_BROWSER_CLIENT.md) ·
[Quest Connection Hub](https://github.com/MesmerPrism/rusty-quest/blob/fb5781031a51bd0709a5941e0a271360b8db49d7/docs/CONNECTION_HUB.md) ·
[Kiosk Direct Link](../docs/DIRECT_OPERATOR.md) ·
[QFM browser and WebUSB status](https://github.com/MesmerPrism/QuestIonAble-File-Manager/blob/f2d939350f8d76000fcd8469107f8c9b29a2740d/docs/browser-pwa-migration.md) ·
[Chrome local-network access](https://developer.chrome.com/docs/modern-web-guidance/guides/security/local-network-access) ·
[Chrome Direct Sockets](https://developer.chrome.com/docs/iwa/direct-sockets)

The standalone status and audio companion uses confirmation on both devices.
The operator selects the headset in the browser chooser. The browser
and Quest display the same comparison digits, and the operator confirms the
match on both devices without entering a code. The PWA saves enrollment per
headset installation and remembers the selected headset. On reopening the PWA,
it attempts to reconnect using its saved enrollment. It reuses
an existing Bluetooth grant where the browser exposes it, and try the saved
authenticated network route when Bluetooth rediscovery is unavailable. If the
saved address is unreachable, offer **Connect headset** to rediscover it with
the browser's chooser and reuse its enrollment. The browser must show when
platform permission or a new confirmation is needed. The headset must let the
wearer revoke enrollment. Enrollment identifies the controller. Starting or
stopping the helper session still belongs to the wearer.

The companion uses Bluetooth for discovery and an authenticated network
connection for its fixed status and audio operations. Test fresh browser setup, reconnect,
selection with two nearby headsets, and revoked enrollment on physical
devices before qualifying it for distribution.

[Pairing source pin](quest-status-audio/browser/vendor/quest-pairing.source.json) ·
[Reconnect implementation](quest-status-audio/browser/controller.js)

## 2. Use ordinary app permissions first

Start with the [standalone status and audio example](quest-status-audio/README.md).
It uses a wearer-started Bluetooth session to read battery, charging, and
session status, identify the headset with a bundled tone, and stop playback.
An earlier bounded headset probe used an ADB-gated debug trigger
and ordinary Android audio playback while another app retained XR focus.
For sustained playback, test a user-started media service and expose its
actual playback and stop state. The short probe leaves long-duration and
post-reboot playback open.

Kiosk supplies normal launch and an optional foreground guard. The Termux
lab demonstrated an allowlisted localhost task while another app was
foregrounded. Its desktop example uses a Quest panel to view Termux's VNC server.
The fleet agent can report signed status and battery/charging from its
explicitly started service. Media, capture, sensors and their permissions
stay with the target app.

[Kiosk controls](../docs/USER_CONTROL.md) ·
[Termux desktop panel](https://github.com/MesmerPrism/quest-termux-lab/tree/3701e821bfe8e7b7e4759904cf5b77d079df1075/examples/spatial-desktop-panel) ·
[Termux headless sidecar findings](https://github.com/MesmerPrism/quest-termux-lab/blob/3701e821bfe8e7b7e4759904cf5b77d079df1075/docs/QUEST_RESULTS_PUBLIC.md#current-findings) ·
[Fleet Agent](https://github.com/MesmerPrism/rusty-quest/blob/fb5781031a51bd0709a5941e0a271360b8db49d7/apps/fleet-agent-android/README.md) ·
[Quest media runtime](https://github.com/MesmerPrism/rusty-quest/blob/fb5781031a51bd0709a5941e0a271360b8db49d7/docs/MEDIA_STREAM_RUNTIME.md) ·
[Android background playback](https://developer.android.com/media/media3/session/background-playback)

## 3. Enable a bounded shell worker when needed

1. Select a fixed existing operation, such as the Kiosk recenter utility.
   Keep the worker separately identified and give it Start, Status, Stop
   and a deadline.
2. Use an eligible external Wi-Fi access point for the attended Wireless
   Debugging setup. The Termux Lab test passed with an offline access point.
   Quest-owned local-only hotspot and peerless Wi-Fi Direct failed on the
   tested firmware. The ADB connection itself was local to the headset.
3. Have the wearer approve any protected request. The already authorized
   Termux client can connect back through loopback once the live debug
   endpoint is available. Confirm shell UID 2000 for the current boot.
4. Give the ordinary companion a fixed local IPC request to the worker and
   verify the operation's effect. The lab separately demonstrated retained
   Binder transfers after TLS loss and authenticated BLE commands after a
   classic Wi-Fi ADB startup. Those were bounded, same-boot tests.
5. Clear shell readiness on worker exit, expiry or reboot. Keep ordinary app
   features available whenever their own runtime is ready.

The standalone app autoboot example and Kiosk's Wi-Fi ADB request after boot
are different paths. The app example still lacks physical boot-to-ready proof.
In the Wi-Fi ADB boot test, a deferred request reached authenticated TLS only
after wearer approval. This leaves unattended cold-boot-to-browser shell
recovery open.

[Standalone autoboot](quest-autoboot/README.md) ·
[Termux Wi-Fi and detached-worker results](https://github.com/MesmerPrism/quest-termux-lab/blob/3701e821bfe8e7b7e4759904cf5b77d079df1075/docs/QUEST_RESULTS_PUBLIC.md) ·
[Attended boot qualification](https://github.com/MesmerPrism/meta-quest-agent-workflow/blob/39a4641d34d806a7604e61dd5dc9e810d86608df/docs/boot-wireless-adb-qualification.md) ·
[Shell recenter](quest-shell-recenter/README.md)

The [developer recovery status panel](quest-recovery-status/README.md) can display
imported boot, approval, shell, and watchdog observations without dispatching a
headset command. Keep those observations distinct from the ordinary Bluetooth
companion's session state.

## Verify the desktop baseline

Run these checks from separate clean checkouts of the documented source
revisions. They need no phone, headset, or ADB transport.

- In Rusty Kiosk run `pwsh -NoProfile -File .\tools\check_repo.ps1`.
  The [repository gate](../docs/VALIDATION.md) uses PowerShell 7.6 or newer,
  Python and the Android build toolchain. It checks source contracts, Gradle
  unit tests and lint, release-channel manifests, and the standalone autoboot
  example build.
- In QFM run `node --test tools/browser-ui/pwa.test.cjs` for the static shell.
  Its [Chromium fixture](https://github.com/MesmerPrism/QuestIonAble-File-Manager/blob/44c469c02c6ee6b290616af16374f64cc43acaab/tools/browser-ui/README.md#L120-L135) exercises installation, offline reload, service-worker update,
  and reconnect against a synthetic loopback server.
- In Rusty Quest run `node --test web/ble-control/gatt-lifetime.test.cjs`.
  The fixture exercises the shared browser GATT lifetime. A desktop browser's
  Bluetooth chooser and a real headset connection are separate checks.

For the standalone companion, first test that an enrolled session can report its
own readiness and start and stop a bundled clip. Use a fixture to exercise
enrollment rejection, stale status, reload, and a lost command response. On
reconnect, look up the original request before offering another playback action.
Require app-side evidence for playing, stopped, or completed. Keep the app's
observed state distinct from a queued request or transport acknowledgment.

## Test the composition on hardware

Start after a cold boot on an enrolled headset. From a supported desktop
Chromium browser, verify status
and one app-owned effect with shell unavailable. Complete attended debugging
approval and read back the current shell identity. Request one fixed privileged
operation and verify its effect. Disconnect, look up the original result without
replaying it, and prove Stop or expiry. Report companion readiness, wearer
approval, shell readiness, and unknown operation outcomes separately.

The ordinary app acceptance is complete when the wearer hears the clip start
and stop while another immersive app retains XR focus. After a headset reboot,
explicitly start the companion and repeat the connection. Test the public
origin in the target mobile browser before qualifying the phone route. The
[utility catalogue](README.md) links the existing parts.
