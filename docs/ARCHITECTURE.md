# Architecture

## Decision

Ship one Spatial SDK APK with two deliberately separate responsibilities:

- `RustyKioskActivity` owns the panel, installed-app catalogue, tag-file state,
  explicit launch requests, and return-to-kiosk lifecycle.
- `KioskAccessibilityService` owns only top-level package/window observation
  and explicit target recovery while a kiosk launch is armed.

The service and activity share app-private guard state. No broadcast control
surface, companion app, Termux process, ADB process, overlay, or HOME role is
required during use.

## Authority

| Concern | Owner |
| --- | --- |
| Installed launchable-app discovery | `InstalledAppRepository` |
| Tag-file bytes and matching | `TagFileStore` / `CatalogAssembler` |
| Search and tag filter | immutable `KioskUiState` projection |
| Normal versus kiosk launch choice | `LaunchController` |
| Armed target and escape state | `GuardStateStore` |
| Window/package transitions | `KioskAccessibilityService` |
| Target runtime behavior | launched application |
| Meta Home and system UI | Horizon OS |

## Panel preview authority

The production panel geometry and stable control identities are shared through
`RustyKioskPanelGeometry` / `RustyKioskPanelControls` and mirrored in
`references/rusty-kiosk-panel-contract.v1.json`.

| Surface | Role |
| --- | --- |
| Browser projection | Fast interactive design with synthetic catalogue data |
| Native Android preview | Production Compose rendering through Layoutlib/Paparazzi |
| Quest APK | Spatial compositor, apparent size, input, keyboard, and placement authority |

The browser owns no package discovery, tag file, guard state, or launch
authority and is never packaged into the APK. The native preview imports the
production Compose/model source directly instead of maintaining a second
native facsimile.

## Escape sequence

```text
Rusty Kiosk --kiosk launch--> target app
target --Home #1/#2--> Meta shell --watchdog recovery--> target
target --Home #3 within 5 s--> Meta shell --disarm/return--> Rusty Kiosk
Rusty Kiosk --Home--> Meta Home
```

Returning to Rusty Kiosk uses an explicit action. If an old Spatial activity
already exists, it finishes its task and starts a fresh MAIN task after a short
teardown delay so a stale panel runtime is not reused.

## Non-scope

- device owner, managed Shared Mode, Android lock task, or tamper resistance;
- intercepting or consuming the hardware Meta button;
- UI content retrieval, Accessibility node traversal, clicks, gestures, or
  global actions;
- force-stopping target or system packages;
- app installation, permission automation, or sideload management;
- high-rate media, tracking, mesh, or rendering data in the tag file.
