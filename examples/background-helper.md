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

## 2. Use ordinary app permissions first

Start with companion status and one app-owned effect, such as playing and
stopping a short clip. A bounded headset probe used an ADB-gated debug trigger
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

## Test the composition

Start after a cold boot on an enrolled headset. From a browser, verify status
and one app-owned effect with shell unavailable. Complete attended debugging
approval and read back the current shell identity. Request one fixed privileged
operation and verify its effect. Disconnect, look up the original result without
replaying it, and prove Stop or expiry. Report companion readiness, wearer
approval, shell readiness, and unknown operation outcomes separately.

A future runnable example can use its own APK and build. Reuse owner adapters
and keep its browser commands typed and bounded. The
[utility catalogue](README.md) links the existing parts.
