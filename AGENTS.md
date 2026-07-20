# Rusty Kiosk Agent Notes

Rusty Kiosk is a public Meta Spatial SDK example. Keep it portable, small, and
free of private-project identities, assets, study flows, device serials, logs,
screenshots, signing material, or generated APKs.

Read `README.md`, `docs/ARCHITECTURE.md`, `docs/TAG_FILE.md`,
`docs/PANEL_PREVIEW.md`, and `docs/VALIDATION.md` before changing behavior.

## Product invariants

- The app is a normal Spatial SDK application, not Android HOME, device owner,
  managed Shared Mode, or tamper-resistant lock task.
- Rusty Kiosk itself never runs under its foreground guard.
- Normal launch disarms the guard before starting the selected app.
- Kiosk launch arms the guard for one explicit target component.
- The first two distinct Meta Home invocations recover that target. The third
  within five seconds disarms the guard and returns to Rusty Kiosk.
- Once Rusty Kiosk is visible, a normal Meta Home press can open Meta Home.
- Accessibility observes package/window transitions only. Do not add UI-tree
  reads, text/view lookup, clicks, gestures, global actions, force-stop, or
  hidden Accessibility activation.
- A name-only tag-file entry that does not match an installed launchable app
  remains visible and is labeled not installed.
- The browser preview is synthetic interactive design tooling, never a runtime
  WebView or installed-app authority.
- The native desktop preview compiles the production Compose panel and is the
  desktop visual authority. Quest remains authoritative for spatial placement,
  compositor output, apparent size, pointer input, and keyboard behavior.
- Keep panel geometry, stable control tags, browser projection, native fixtures,
  and `references/rusty-kiosk-panel-contract.v1.json` synchronized.

Use `$meta-quest-workflow` before any headset, ADB, APK install/launch, logcat,
screenshot, or physical-button validation. Keep raw device evidence private.

## Checks

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\check_repo.ps1
```
