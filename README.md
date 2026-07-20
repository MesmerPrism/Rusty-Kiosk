# Rusty Kiosk

Rusty Kiosk is a small public Meta Quest launcher example built with Meta
Spatial SDK. It renders one Jetpack Compose panel over system passthrough,
browses launchable apps installed on the headset, assigns searchable tags, and
offers normal or soft-kiosk launch.

The project is intentionally not a Home replacement or managed-device kiosk.
Its optional Accessibility service is a foreground watchdog:

1. **Normal launch** disarms the watchdog and opens the selected app.
2. **Kiosk launch** arms the watchdog for the selected explicit activity.
3. The first two distinct Meta Home invocations restore the selected app.
4. The third Home invocation within five seconds disarms the watchdog and
   returns to Rusty Kiosk.
5. The watchdog is inactive in Rusty Kiosk, so Home then opens Meta Home
   normally.

## What the first example includes

- one Spatial SDK Compose panel;
- system passthrough with no room model or skybox;
- launchable-app discovery for ordinary Android, 2D, Leanback, and Quest VR
  front doors;
- installed-app search by label, package, or tag;
- tag filtering;
- tag editing from the panel;
- hot reload of an externally editable JSON tag file;
- unresolved name-only entries shown as **Not installed**;
- normal and soft-kiosk launch actions;
- package/window-only Accessibility monitoring with UI retrieval disabled.

## Build

Requirements:

- Android Studio / Android SDK 34;
- JDK 17;
- a Meta Quest device on a compatible Horizon OS version;
- Meta Spatial SDK dependencies available through the configured Maven
  repositories.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/` and remains
ignored by Git.

## Panel design and onboarding visuals

Rusty Kiosk includes the same three-tier panel workflow used for precise
Spatial SDK iteration:

1. an interactive browser projection for fast design work;
2. a native Android render compiled from the production Compose panel for
   typography, clipping, and alignment checks;
3. the Quest APK as final authority for compositor output, apparent size,
   pointer input, keyboard behavior, and spatial placement.

Start the synthetic browser designer:

```powershell
pwsh -NoProfile -File .\tools\Start-RustyKioskPanelBrowserPreview.ps1
```

Then open
`http://127.0.0.1:8767/tools/rusty-kiosk-panel-browser-preview/`. It supports
search, tag filters, tag editing, installed and missing-app states, guard setup,
launch simulations, deterministic state import/export, and clean capture URLs
for onboarding visuals.

Generate native renders from the real `RustyKioskPanel` source:

```powershell
pwsh -NoProfile -File .\tools\Export-RustyKioskNativePanelPreview.ps1
```

Refresh the browser designer and choose **Native Android** or the aligned
comparison view. Generated PNGs and their source-binding manifest stay under
ignored `artifacts/` paths. See [Panel preview workflow](docs/PANEL_PREVIEW.md).

## Enable the optional watchdog

Horizon OS may not expose the normal Accessibility settings page. For an
attended development headset, enable only this exact component with the helper:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Enable-RustyKioskAccessibility.ps1 `
  -Serial <quest-serial>
```

The helper preserves other enabled Accessibility services. This is a one-time
device setup and is not performed by the app itself.

## Tag file

Rusty Kiosk creates this file on first launch:

```text
/sdcard/Android/data/io.github.mesmerprism.rustykiosk/files/tags/app-tags.v1.json
```

Edit or replace it while the app is running; a directory observer reloads it.
The panel also has a manual reload action. Entries may identify an app only by
its displayed name. Missing entries remain searchable and filterable.

See [Tag file](docs/TAG_FILE.md) for the schema and matching rules.

## Important limitations

- Installed-app inventory is sensitive data. This example declares
  `QUERY_ALL_PACKAGES` because browsing installed apps is its primary purpose.
  Review current Meta distribution and privacy requirements before publishing
  a binary.
- The watchdog is not lock task, device owner, or managed Shared Mode. Meta
  Home can appear briefly, and the service can be disabled or force-stopped.
- Home detection relies on observed Horizon package/activity signals and must
  be revalidated after Horizon updates.
- Some installed packages do not expose a public launchable activity and
  therefore cannot appear as launch targets.

## License

AGPL-3.0-or-later. See `LICENSE`.

Meta Spatial SDK and other dependencies retain their own licenses and terms.
See `THIRD_PARTY_NOTICES.md`.
