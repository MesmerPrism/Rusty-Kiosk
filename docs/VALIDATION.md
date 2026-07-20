# Validation

## Host gate

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\check_repo.ps1
```

The gate checks public-boundary terms, manifest privacy constraints, Kotlin
unit tests, browser/native panel contracts, Android lint, and a debug APK
assembly.

Unit tests cover:

- tag-file parsing and normalization;
- package-first and name-only catalogue matching;
- missing name-only entries remaining visible under their tags;
- search plus tag filtering;
- normal launch disarming before launch;
- first/second Home recovery and third-Home return;
- Home debounce and five-second escape-window reset;
- generic and exact Horizon signals from one Home press counting only once.

The static guard checks additionally require Rusty Kiosk to disarm itself when
its own package becomes foreground and prohibit Accessibility UI-tree access,
global actions, gestures, and Android HOME-role declarations.

The standard host gate runs the interactive browser model and verifies that
the production Compose panel, shared geometry/control contract, browser
projection, and source-bound native host remain synchronized. Run the deeper
native visual gate separately when panel visuals change:

```powershell
pwsh -NoProfile -File .\tools\Test-RustyKioskPanelPreview.ps1 -RenderNative
```

That produces ignored source-bound images; it does not replace the headset
gate below.

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

Injected `KEYCODE_HOME` can validate the Android event path, but it does not
replace a physical Meta-button witness.
