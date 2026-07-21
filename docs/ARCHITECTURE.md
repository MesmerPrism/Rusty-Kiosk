# Architecture

## Decision

Ship two same-signer APKs with deliberately separate authority:

- `app` is the ordinary Spatial SDK application. `RustyKioskActivity` owns the
  panel, catalogue, tag state, explicit launches, user-control projection, and
  typed operator adaptation. Its wearer-enabled `OperatorBridgeService` owns a
  bounded authenticated local listener and app-owned staging. Android
  `PackageInstaller` owns attended installation. `KioskAccessibilityService`
  owns only top-level package/window observation while a kiosk launch is armed.
- `setup-helper` is non-launchable and has no network permission. It may be
  granted `WRITE_SECURE_SETTINGS` once over USB-C and accepts only an explicit,
  signature-protected fixed-operation broadcast from the main app.

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
| Search and tag filter | immutable `KioskUiState` projection |
| Normal versus kiosk launch choice | `LaunchController` |
| Armed target and escape state | `GuardStateStore` |
| Window/package transitions | `KioskAccessibilityService` |
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
| Release host admission | `DUMP`-protected `RustyKioskOperatorProvider.call()` v2 |
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

Returning to Rusty Kiosk starts a fresh MAIN task after a short teardown delay
so a stale Spatial panel runtime is not reused.

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

## Non-scope

- device owner, managed Shared Mode, Android lock task, or tamper resistance;
- intercepting or consuming the hardware Meta button;
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
