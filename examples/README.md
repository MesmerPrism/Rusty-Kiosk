# Reusable Quest utility compositions

Use this catalogue to find Quest helpers by capability. The
[background helper walkthrough](background-helper.md) shows how the parts fit
together. The [distribution guide](../docs/UTILITY_DISTRIBUTION.md) covers Store
Lite, Meta previews, SideQuest, developer bundles and managed headsets.
Each runnable example has its own build, package and permission scope.
The consuming app owns its lifecycle and effects. Shared modules stay in their
owner repositories.

| Capability | Start here | Authority and evidence |
| --- | --- | --- |
| Provision and connect | [QFM Kiosk integration](https://github.com/MesmerPrism/QuestIonAble-File-Manager/blob/f2d939350f8d76000fcd8469107f8c9b29a2740d/docs/rusty-kiosk-integration.md) and [Kiosk Direct Link](../docs/DIRECT_OPERATOR.md) | Authorized USB for initial provisioning. The paired link supplies typed routine control and restricted staging afterward. Wearer confirmation still applies to the attended installer. |
| Boot and wearer-aware launch | [Integration notes](boot-and-wearer.md) and [standalone example](quest-autoboot/README.md) | Public example source with host-testable policy; Quest boot and target-readiness acceptance remain unproven. |
| BLE discovery and phone control | [Integration notes](ble-phone-control.md), [public BLE link test](https://mesmerprism.com/quest-ble-test/) and [shared Quest browser client](https://github.com/MesmerPrism/rusty-quest/blob/fb5781031a51bd0709a5941e0a271360b8db49d7/docs/BLE_BROWSER_CLIENT.md) | Browser chooser and ordinary Android Bluetooth grants. The standalone link test covers one bounded rendezvous. An enrolled app owns its commands and confirms their effects. |
| Wireless Debugging after boot and local shell | [Kiosk settings](../docs/USER_CONTROL.md) and [Termux Lab results](https://github.com/MesmerPrism/quest-termux-lab/blob/3701e821bfe8e7b7e4759904cf5b77d079df1075/docs/QUEST_RESULTS_PUBLIC.md) | The separately provisioned helper can request Wireless Debugging. Local shell and bounded post-disconnect IPC were demonstrated after attended setup. Unattended cold-boot recovery is open. |
| Background tasks and status | [Background helper walkthrough](background-helper.md#2-use-ordinary-app-permissions-first) | Termux for local Linux tasks, a native agent for fleet check-ins, and Android media APIs for playback. The audio prototype's ADB debug trigger and the playback permission boundary are separate. |
| Browser status and a short audio clip | [Standalone Quest status and audio example](quest-status-audio/README.md) | Wearer-started Bluetooth session with an app-owned playback result. The example has its own Android build and static PWA. Physical phone pairing and XR audio are separate acceptance checks. |
| Shell recenter developer utility | [Console utility and custody contract](quest-shell-recenter/README.md) | Fixed readiness/recenter/passive-status consumer of the pinned Quest provider; genuine Android shell privilege required. Host tests do not establish a headset effect. |
| Keep a lab headset awake | [QFM awake control](https://github.com/MesmerPrism/QuestIonAble-File-Manager/blob/f2d939350f8d76000fcd8469107f8c9b29a2740d/docs/quest-awake-control.md) and [standalone watchdog proof](https://github.com/MesmerPrism/QuestIonAble-File-Manager/blob/0d6165ba4081939ff56e63340dd6720e96aa820e/helpers/quest-proximity-watchdog/LIVE_VALIDATION.md) | Separate shell-backed utilities. The standalone watchdog has bounded physical proof on a published development branch outside the packaged QFM releases. Reestablish a required hold after reboot. |
| WebSocket control | [Rusty Quest Connection Hub](https://github.com/MesmerPrism/rusty-quest/blob/1ea0d3b392c7d2fce079f2265d383832df64b04b/docs/CONNECTION_HUB.md) and its [Spatial Video provider example](https://github.com/MesmerPrism/rusty-quest/blob/1ea0d3b392c7d2fce079f2265d383832df64b04b/apps/spatial-video-control-example-android/AGENTS.md) | Shared transport with an app-owned executor. An available listener can serve status while the selected executor is unavailable. |
| Capture and media | [Quest media runtime](https://github.com/MesmerPrism/rusty-quest/blob/fb5781031a51bd0709a5941e0a271360b8db49d7/docs/MEDIA_STREAM_RUNTIME.md) and [display capture routes](https://github.com/MesmerPrism/rusty-quest/blob/fb5781031a51bd0709a5941e0a271360b8db49d7/apps/display-stream-android/README.md) | App-owned camera and consented display capture have separate permissions from shell-only mirroring or special compositor capture. Keep media sessions and capture lifecycle with their app owners. |

The [Rusty Quest repository](https://github.com/MesmerPrism/rusty-quest)
owns Android boot-event, wearer-signal, BLE/GATT, and WebSocket transport
adapters. Its existing
[`rusty-quest-broker-transport`](https://github.com/MesmerPrism/rusty-quest/tree/1ea0d3b392c7d2fce079f2265d383832df64b04b/crates/rusty-quest-broker-transport)
is the shared RFC 6455 implementation for supported broker placements. The
[Manifold repository](https://github.com/MesmerPrism/rusty-manifold) owns
portable shared command admission, replay, leases, revisions, and authority
receipts when those contracts are selected. A local boot preference does not
need a Manifold session merely because Manifold exists. The application owns
its own feature choice, package identity, permissions, domain actions, and
effective-state evidence.

External operator tools can exercise and report on these compositions, but
they do not become the runtime controller. In particular, host-side APK/ADB
operations do not replace Android component lifecycle or on-device package
installation contracts. A standalone Connection Hub is one placement choice;
an embedded broker may be appropriate under the same owner contracts.
Hostess can project host checks, while QFM handles exact host-side APK/ADB
effects; neither proves an application's command took effect.

For the WebSocket composition, the Hub listener can remain active after a
provider Activity stops and unregisters its surfaces. The provider's effect
executor may no longer be available. Treat a pending action as unresolved
until its bound provider receipt and separately observed app state establish
the result; a transport acknowledgment or accepted command is insufficient.

For every future runnable example, record the exact owner source revision,
selected modules, dependencies and licence, package/signing identity,
permissions, component lifetimes, command authority, request/receipt meanings,
timeout and reconnect behavior, and cleanup. Keep any example shell separately
identified and out of existing Kiosk release builds until its own contract and
validation are reviewed.
