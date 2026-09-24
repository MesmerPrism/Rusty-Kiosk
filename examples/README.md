# Reusable Quest utility compositions

This catalogue helps an application select reusable Quest capabilities. It is
documentation only: no example APK, library, manifest permission, or service is
included in Rusty Kiosk releases. An example demonstrates a composition; the
owner library holds reusable implementation; the consuming app owns its
selected capabilities, lifecycle, policy, and effects.

| Composition | Start here | Current status |
| --- | --- | --- |
| Boot and wearer-aware launch | [Integration notes](boot-and-wearer.md) | Design guidance from a bounded private prototype; no public reusable implementation or device acceptance claimed here. |
| BLE phone control | [Integration notes](ble-phone-control.md) and [public BLE link test](https://mesmerprism.com/quest-ble-test/) | The link test exercises one bounded rendezvous exchange; application commands and a reusable GATT adapter remain separate work. |
| WebSocket control | [Rusty Quest Connection Hub](https://github.com/MesmerPrism/rusty-quest/blob/1ea0d3b392c7d2fce079f2265d383832df64b04b/docs/CONNECTION_HUB.md) and its [Spatial Video provider example](https://github.com/MesmerPrism/rusty-quest/blob/1ea0d3b392c7d2fce079f2265d383832df64b04b/apps/spatial-video-control-example-android/AGENTS.md) | Existing owner source; link to it rather than copying it into Kiosk. |

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
