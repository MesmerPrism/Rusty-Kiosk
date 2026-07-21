# Validation

## Host gate

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\check_repo.ps1
```

The gate checks public-boundary terms, both APK manifests, Kotlin unit tests,
browser/native panel contracts, Android lint, release CLI exclusion, and debug
assembly for both APKs.

Unit tests cover:

- tag-file parsing and normalization;
- package-first and name-only catalogue matching;
- missing name-only entries remaining visible under their tags;
- search plus tag filtering;
- normal launch disarming before launch;
- normal launch retaining resumable-task flags while initial kiosk launch uses
  a fresh-task policy;
- visible and typed kiosk launch handoff ignoring only a matching, bounded
  stale Rusty Kiosk foreground event;
- first/second Home recovery and third-Home return;
- Home debounce and five-second escape-window reset;
- generic and exact Horizon signals from one Home press counting only once;
- fixed setup-helper result parsing and fail-closed request matching;
- exact-component Accessibility enable/disable list construction;
- preservation of other enabled Accessibility services;
- typed CLI parsing, payload bounds, value rules, and unknown-command rejection.

The static guard checks additionally require Rusty Kiosk to disarm itself when
its own package becomes foreground and prohibit Accessibility UI-tree access,
global actions, gestures, and Android HOME-role declarations.
They also reject `WRITE_SECURE_SETTINGS` in the main Rusty Kiosk manifest,
require the service-owned `disableSelf()` path, and require the separate helper
to remain signature-protected, non-launchable, non-networked, and fixed-operation
only. The serial-scoped provisioning script is checked for both APKs and the
one-time helper grant.
The static guard additionally requires the exported CLI activity to remain in
the debug source set, require sender-held `android.permission.DUMP`, use
serial-scoped ADB, and avoid process execution or raw command forwarding.
The exact watchdog-transition receiver is held to the same debug-only and
`DUMP` boundary and accepts only one fixed logical Home transition.

The standard host gate runs the interactive browser model and verifies that
the production native panel, shared geometry/control contract, browser
projection, and source-bound native host remain synchronized. Run the deeper
native visual gate separately when panel visuals change:

```powershell
pwsh -NoProfile -File .\tools\Test-RustyKioskPanelPreview.ps1 -RenderNative
```

That produces ignored source-bound images; it does not replace the headset
gate below.

The gate also requires both Quest keyboard capabilities in the main manifest:
`com.oculus.feature.VIRTUAL_KEYBOARD` and
`oculus.software.overlay_keyboard`.

## Headset gate

Device validation is intentionally separate from source validation. Use an
explicit Quest serial and the public Meta Quest workflow. A complete attended
run should prove:

1. the app opens one panel over passthrough with no room or skybox;
2. at least one sideloaded and one Meta-distributed launchable app are listed;
3. an externally edited tag file reloads without restarting the app;
4. a name-only missing entry appears and is labeled not installed;
5. normal launch leaves the watchdog disarmed;
6. kiosk launch restores the target after Home #1 and Home #2;
7. Home #3 within five seconds returns to a fresh Rusty Kiosk panel;
8. one Home press from Rusty Kiosk reaches Meta Home;
9. no Rusty Kiosk or target-package fatal occurs in the bounded log window.
10. both native Android text fields open the Meta keyboard on a normal
    wearer click and accept text;
11. the status strip distinguishes setup-helper readiness, Wi-Fi ADB,
    Accessibility, and Meta Home;
12. the setup helper is unavailable before installation, reports **Needs USB-C
    setup** before its grant, and reports **Ready** only after serial-scoped
    provisioning;
13. Accessibility can be enabled and disabled through the fixed helper while
    every other enabled Accessibility service is preserved;
14. disabling Wi-Fi ADB leaves Accessibility unchanged;
15. the restart request is off by default, can be enabled and disabled, and
    causes a new request only when enabled;
16. after restart or a later manual request, Meta approval remains visible and
    attended; the panel reports only effective setting state;
17. **Exit to Meta Home** disarms pending guard state and opens Meta Home.

Run app actions through `tools/Invoke-RustyKioskCli.ps1`; display-coordinate
touch injection is not accepted. `focus-search` and `focus-tag-editor` must
route to the production native fields and produce an app marker showing the
explicit IME request was issued without logging text. A normal wearer click is
still required for Horizon to grant the Spatial display its served-view input
connection and visually show the keyboard.
System Home transitions may use an explicit Android HOME activity launch for
CLI coverage, but that remains system-action evidence rather than physical
Meta-button or Touch-controller parity. The CLI may trigger every fixed app
action, but USB-C authority provisioning and protected Android/Meta consent
remain attended gates and are never approved by the Rusty Kiosk CLI.

For deterministic watchdog acceptance, arm a target through the ordinary CLI
and run `tools/Invoke-RustyKioskGuardCli.ps1 -Count 3`. Require two
`schedule_recovery` receipts with `guard_armed=true`, followed by one
`disarm_and_return` receipt with `guard_armed=false`. Keep the Android HOME
wrapper and a physical Meta-button run as separate integration witnesses.
