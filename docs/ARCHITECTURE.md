# Architecture

## Decision

Ship two same-signer APKs with deliberately separate authority:

- `app` is the ordinary Spatial SDK application. `RustyKioskActivity` owns the
  panel, catalogue, tag state, explicit launches, user-control projection, and
  typed operator adaptation. Its wearer-enabled `OperatorBridgeService` owns a
  bounded authenticated local listener and app-owned staging. Android
  `PackageInstaller` owns attended installation. `KioskAccessibilityService`
  owns top-level package/window observation while a kiosk launch is armed and
  consumes an optional authenticated foreground-loss advisory from that exact
  target. Accessibility remains the fallback and the sole Meta Home and
  Triple-Home authority.
- `setup-helper` is non-launchable and has no network permission. It may be
  granted `WRITE_SECURE_SETTINGS` once over USB-C and accepts only an explicit,
  signature-protected fixed-operation broadcast from the main app.

The separate native 2D `launcher` implementation has no authority in either
APK. It is released under two exact package identities because Meta Store apps
and Quest Private Apps occupy separate distribution namespaces. The Store and
Business builds use the same source, launcher signer, target package, target
signer pin, manifest capabilities, and handoff behavior. Their package identity
is the only intentional runtime difference.

The main APK never receives secure-settings authority. The helper exposes no
shell, terminal, free-form string operation, package/component choice, file
path, endpoint, or generic intent. Both APKs must use the same signing key.

## Fixed setup protocol

`SetupHelperOperation` is the complete request vocabulary:

- status;
- request or disable Wi-Fi ADB;
- enable or disable Rusty Kiosk's exact Accessibility component;
- enable or disable the preference to request Wi-Fi ADB after boot.

The helper preserves all other enabled Accessibility components. Its boot
receiver runs only when the wearer opted in. A request can cause Horizon OS to
show its protected Wi-Fi ADB approval surface, but neither APK can approve it.

Results return through an ordered broadcast callback and contain only the
request id, fixed operation, success, helper readiness, restart preference, and
a bounded message. The main app independently reads effective Accessibility
and Wi-Fi ADB state after completion.

## Authority

| Concern | Owner |
| --- | --- |
| Installed launchable-app discovery | `InstalledAppRepository` |
| Tag-file bytes and matching | `TagFileStore` / `CatalogAssembler` |
| Dedicated per-app launch requirement | strict tag-document v2 field / `ActiveRequirementLaunchCoordinator` |
| Ordinary Wi-Fi observation | read-only Android `WifiManager.isWifiEnabled` |
| Unmet requirement remediation | fixed Android `ACTION_WIFI_SETTINGS` surface |
| Search and tag filter | immutable `KioskUiState` projection |
| Retained search, active tag filter, and visible selection | app-private `KioskBrowsingStateStore` |
| Normal versus kiosk launch choice | `LaunchController` |
| Armed target and escape state | `GuardStateStore` |
| Window/package transitions | `KioskAccessibilityService` |
| Optional target foreground-loss advisory | `ForegroundSignalProvider` / `KioskAccessibilityService` |
| Target protocol and signing identity at arm time | `ForegroundSignalCapabilityDetector` / `GuardStateStore` |
| Binder caller UID/package and call-time signer readback | Android `ContentProvider` / `PackageManager` |
| Meta Home and protected approval UI | Horizon OS |
| Visible passthrough composition | Spatial SDK `Scene.enablePassthrough` |
| Passthrough color style | retained 16³ `Lut` through `Scene.setPassthroughLUT` |
| Passthrough effective state | Spatial SDK `Scene.isSystemPassthroughEnabled` readback |
| Visible status and consent actions | `RustyKioskActivity` / `UserControlState` |
| Fixed privileged operations | `setup-helper` / `SetupExecutor` |
| Effective Accessibility state | Android `AccessibilityManager` readback |
| Effective Wi-Fi ADB setting | Android `Settings.Global` readback |
| One-time helper provisioning | serial-scoped host script over USB-C ADB |
| Debug CLI admission | debug manifest + sender-held `android.permission.DUMP` |
| CLI vocabulary and bounds | `RustyKioskCliProtocol` |
| CLI queue and result receipt | app-private `RustyKioskCliStore` |
| App action semantics | the same activity handlers used by the native panel |
| Release host admission | `DUMP`-protected `RustyKioskOperatorProvider.call()` v4 |
| Typed request lifecycle | durable provider epoch + request ID + bounded queue/result tombstones |
| USB Direct Link bootstrap | fixed provider methods + app-private generation-bound ephemeral sessions |
| Fixed tag transfer | ordered bounded provider chunks + SHA-256/schema/atomic activation |
| Direct-link opt-in and pairing-code rotation | visible `RustyKioskActivity` controls |
| Direct request admission | expiry + replay store + HMAC-SHA-256 body/response checks |
| Direct file bytes | bounded app-owned `operator-staging` directory |
| Local APK transaction | Android `PackageInstaller` + matching app-private receipt |
| ADB fallback/bootstrap | authorized serial-scoped host |

## Panel preview authority

The production geometry and stable control identities are shared through
`RustyKioskPanelGeometry` / `RustyKioskPanelControls` and mirrored in
`references/rusty-kiosk-panel-contract.v1.json`.

| Surface | Role |
| --- | --- |
| Browser projection | Fast interactive design with synthetic catalogue data |
| Native Android preview | Production Compose rendering through Layoutlib/Paparazzi |
| Quest APK | Spatial compositor, apparent size, input, keyboard, and placement authority |

The browser owns no package discovery, tag file, guard state, launch authority,
or device setting. The native preview imports the source-bound design/model
source directly.

## Escape sequence

```text
Rusty Kiosk --kiosk launch--> target app
target --Home #1/#2--> Meta shell --watchdog recovery--> target
target --Home #3 within 5 s--> Meta shell --disarm/return--> Rusty Kiosk
Rusty Kiosk --Home--> Meta Home
Rusty Kiosk --Exit to Meta Home--> Meta Home
```

## Active launch requirements

`any`, `wifi-on`, and `wifi-off` are one dedicated field, separate from passive
searchable tags. Legacy v1 documents migrate as `any`; the first explicit edit
upgrades the document to strict v2. Unknown handlers, duplicates, conflicts,
unavailable state, or schema drift fail closed.

Both launch buttons share one preflight before guard handoff or target launch.
An unmet requirement creates one two-minute process-scoped pending binding and
opens Android's fixed Wi-Fi settings Activity. Rusty Kiosk does not change
ordinary Wi-Fi and never reads the Wi-Fi ADB setting for this decision. Return
revalidates the exact entry, target component, signing/install identity, launch
mode, document digest, requirement digest, and current Wi-Fi state. A process
restart, selection disappearance, update, edit, cancellation, or expiry drops
the pending launch; no target or guard action is replayed.

## Provider-v4 bootstrap and request lifecycle

The DUMP-protected provider adds status, exact queued-request cancellation, and
explicit Direct Link status/enable/disable methods. Requests are keyed by an
app-private provider epoch plus request ID, expire after two minutes, move to a
per-request terminal tombstone, and are never reconstructed after a claimed
request loses its process. Read-only status does not enqueue. Cancellation
fails closed if application or any terminal result wins the race.

Direct enable issues a one-time provider response containing a random 32-byte
session key valid for five minutes and one Stable/Labs-isolated bridge
generation. Issuance is rate/concurrency bounded and audited by IDs and times
without logging the secret. The session authenticates only the fixed Direct
Link capability through `X-Rusty-Session-Id`; the durable pairing code remains
on-headset. Generation changes, expiry, disablement, and code rotation revoke
the session. The host-side exact-serial wrapper is evidence owned by the host;
the Android app binds channel, package, DUMP caller, capability, and generation,
not an ADB serial it cannot observe.

Operation-ID replay authority is a fixed 4096-entry, non-evicting ledger bound
to a durable app-private bootstrap-issuance epoch. Bridge-generation changes never
clear it. Saturation, malformed state, or epoch mismatch rejects issuance; only
the app-data reset that creates a new bootstrap-issuance epoch resets this scope.
The private session-state schema initializes its replay arrays only when no state
exists. Once stored, a missing, null, or wrong-type array is integrity damage and
cannot be replaced with an empty ledger.

The network secret and cleanup authority have separate lifetimes. A bounded
non-secret tombstone records the originating operation/session/generation,
whether bootstrap actually enabled the link, cleanup state, and a 24-hour
deadline. Only that owned enable can advance to `disable_dispatched`; the
record then tracks the post-disable generation so an operation-ID-only DUMP
recovery call can re-dispatch STOP after response loss. Current-generation
stopped readback consumes it. Direct install v2 requires ordered
`{name, bytes, sha256}` commitments and verifies count and digest from the same
source handle copied to PackageInstaller before commit.
If session abandonment throws, Kiosk treats either a successful abandon return
or PackageInstaller absence readback as cleanup confirmation. Present or
unreadable session state remains `cleanup-required` and incomplete; repeating
the same install body may retry cleanup but cannot start another install under
that request ID. A private v2 receipt stores the exact ordered commitments and a
canonical digest while the public receipt shape remains v1. Cleanup compares both
before touching PackageInstaller. Receipt inspection distinguishes absent, valid,
and damaged bytes; only absent permits a new session, including under concurrency.

Returning to Rusty Kiosk starts a fresh MAIN task after a short teardown delay
so a stale Spatial panel runtime is not reused.
The fresh task restores the wearer's last search text, active tag filter, and
selected visible app from app-private preferences. Selecting **All apps** or
clearing the search removes the corresponding retained filter. A selected tag
is also cleared if a tag-file reload removes it from the catalogue entirely,
and the selection moves to the first visible result if its prior app is no
longer visible.

Rusty Kiosk declares Meta's optional passthrough capability and reapplies its
persisted style whenever the Spatial scene becomes ready or resumes. Natural
uses an identity LUT. Contour LUT uses hard luminance-to-color bands so scene
contours are conspicuous. A point LUT is not neighborhood edge detection. The
app does not create a second `XR_FB_passthrough` layer because Spatial SDK owns
frame submission; an unsubmitted native layer would have no visual authority.

Normal launch may resume an existing target task. Initial kiosk launch clears
the target's old task so hidden panels or stopped visual output cannot leak in
from a previous XR session. Watchdog recovery then reorders and resumes that
fresh kiosk session without clearing its state.

## Foreground-signal authority

`foreground-signal-client` is a small Android library with no Spatial SDK,
OpenXR, game-engine, Accessibility, launch, or recovery-policy dependency. A
target advertises protocol v2 through application metadata and calls one fixed
provider method only after an app-owned aggregate lifecycle or engine signal
confirms application-level foreground loss. An Activity top-resumed callback
and any fixed-delay inference are deliberately insufficient.

The library's product-channel build property defaults to `stable` and accepts
only `stable` or `labs`. It derives the fixed stable or Labs provider authority
at build time; it cannot accept an arbitrary authority or select a provider at
runtime. This keeps co-installable channel authority separate while preserving
one protocol and admission policy.

Kiosk records a cryptographically random, nonzero per-arm generation plus the
target's exact protocol, canonical signing-certificate lineage, and
PackageManager last-update/version identity. Call-time admission requires the
immediate Binder package to equal the armed target, the UID package set to
contain only that target, and current metadata, signer lineage, and installation
identity to match the recorded values. Package replacement, signer rotation,
shared UID, multiple current signers, missing metadata, stale queued signals,
and re-arm races reject.

An accepted signal enters `observeForegroundLoss`. Exact and generic Home
counting remains exclusively on the Accessibility route. Both routes share the
same generation-bound recovery engine and bounded attempt claims.

## Non-scope

- device owner, managed Shared Mode, Android lock task, or tamper resistance;
- intercepting or consuming the hardware Meta button;
- treating an app foreground-loss callback as evidence of a Home press;
- UI content retrieval, Accessibility node traversal, clicks, gestures, or
  global actions;
- force-stopping target or system packages;
- silent or unattended app installation, installer-prompt approval, downgrade,
  test-only, or automatic runtime-permission grants through the direct link;
- `WRITE_SECURE_SETTINGS` in the main Rusty Kiosk APK;
- raw shell, terminal UI, arbitrary package/component input, unrestricted
  paths, or arbitrary network commands;
- display-coordinate touch injection as acceptance evidence;
- a debug CLI activity in release builds, arbitrary intents through the debug CLI,
  USB-C provisioning through the CLI, or protected-prompt approval;
- a generic release provider query, protected-data browser, shell proxy,
  component launcher, or background app-management plane;
- fleet discovery, multi-device scheduling, online relays, or remote management;
- high-rate media, tracking, mesh, or rendering data in the tag file.
