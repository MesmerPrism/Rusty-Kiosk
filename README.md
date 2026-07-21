# Rusty Kiosk

Rusty Kiosk is a small public Meta Quest launcher example built with Meta
Spatial SDK. It renders one native Android panel over system passthrough,
browses launchable apps installed on the headset, assigns searchable tags, and
offers normal or soft-kiosk launch.

The project is intentionally not a Home replacement or managed-device kiosk.
Its optional Accessibility service is a foreground watchdog:

1. **Normal launch** disarms the watchdog and opens the selected app.
2. **Kiosk launch** starts a fresh task and arms the watchdog for the selected
   explicit activity. Watchdog recovery resumes that fresh kiosk session.
   A target-scoped five-second handoff window prevents a trailing launcher
   window event from immediately disarming that new target.
3. The first two distinct Meta Home invocations restore the selected app.
4. The third Home invocation within five seconds disarms the watchdog and
   returns to Rusty Kiosk.
5. The watchdog is inactive in Rusty Kiosk, so Home then opens Meta Home
   normally.

## What the first example includes

- one Spatial SDK native Android panel;
- system passthrough with no room model or skybox;
- launchable-app discovery for ordinary Android, 2D, Leanback, and Quest VR
  front doors;
- installed-app search by label, package, or tag;
- tag filtering;
- tag editing from the panel;
- hot reload of an externally editable JSON tag file;
- unresolved name-only entries shown as **Not installed**;
- normal and soft-kiosk launch actions;
- package/window-only Accessibility monitoring with UI retrieval disabled;
- an always-visible status strip for Wi-Fi ADB, Accessibility, and Meta Home;
- an explicit, reversible user-control center backed by a dedicated fixed-operation setup helper;
- native Android text inputs using an explicit Quest keyboard path;
- a typed, ADB-shell-protected debug CLI for stable wearer-equivalent testing.

## Build

Requirements:

- Android Studio / Android SDK 34;
- JDK 17;
- a Meta Quest device on a compatible Horizon OS version;
- Meta Spatial SDK dependencies available through the configured Maven
  repositories.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug :setup-helper:assembleDebug
```

The main debug APK is generated under `app/build/outputs/apk/debug/`; the
non-launchable setup helper is generated under
`setup-helper/build/outputs/apk/debug/`. Both remain ignored by Git and must be
signed with the same key.

## Typed debug CLI

Debug builds include a bounded CLI entrypoint for repeatable headset tests
without display-coordinate touch injection. It invokes the same catalogue,
tag, launch, and user-control handlers as the panel and returns a structured
JSON state receipt.

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-RustyKioskCli.ps1 `
  -Serial <quest-serial> `
  -Command set-search `
  -Value browser
```

The component is present only in debug builds and requires the sender-held
Android `DUMP` permission, which allows ADB shell while excluding ordinary
headset applications. It is an allowlisted app-control protocol, not a raw
shell, generic intent bridge, controller emulator, or protected-prompt bypass.
An additional debug-only guard CLI applies exactly one logical watchdog Home
transition per command, so the two-recovery/third-return state machine can be
tested deterministically without touch or key injection. Android HOME activity
and physical Meta-button runs remain separate integration witnesses.
See [Typed debug CLI](docs/CLI.md).

## Panel design and onboarding visuals

Rusty Kiosk includes the same three-tier panel workflow used for precise
Spatial SDK iteration:

1. an interactive browser projection for fast design work;
2. a source-bound native Android design render for typography, clipping, and
   alignment checks;
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

Generate native design renders from the source-bound `RustyKioskPanel` projection:

```powershell
pwsh -NoProfile -File .\tools\Export-RustyKioskNativePanelPreview.ps1
```

Refresh the browser designer and choose **Native Android** or the aligned
comparison view. Generated PNGs and their source-binding manifest stay under
ignored `artifacts/` paths. See [Panel preview workflow](docs/PANEL_PREVIEW.md).

## Dedicated no-terminal setup

Wi-Fi ADB and Accessibility are independent opt-ins. Rusty Kiosk does not
enable either automatically. The main APK never holds `WRITE_SECURE_SETTINGS`,
and neither APK contains a shell, terminal UI, network listener, or generic
command surface.

For a new headset, enable developer USB debugging outside Rusty Kiosk, connect
USB-C, then install and provision both APKs in one serial-scoped step:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Provision-RustyKiosk.ps1 `
  -Serial <quest-serial>
```

The script grants `WRITE_SECURE_SETTINGS` only to the dedicated, same-signer
setup helper. It launches Rusty Kiosk but enables neither Wi-Fi ADB nor
Accessibility.

Open **User controls** in the panel to:

- verify that the dedicated setup helper is provisioned;
- request Wi-Fi ADB and respond to Meta's visible system prompt;
- opt in or out of requesting Wi-Fi ADB again after restart;
- enable or disable only Rusty Kiosk's Accessibility service;
- disable Wi-Fi ADB again;
- exit directly to Meta Home.

The Accessibility service can disable itself even when the setup helper is
unavailable. Once the helper has been provisioned, Rusty Kiosk can request
Wi-Fi ADB again later even when the previous transport is off. Horizon OS
still owns the approval UI; the app and CLI cannot approve it.

See [Transparent user controls](docs/USER_CONTROL.md) for setup, status,
reboot recovery, privacy, and complete revocation instructions.

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
- The optional setup-helper/Wi-Fi ADB path is an attended developer-headset feature,
  not a consumer permission flow, device-management plane, or unattended boot
  bypass. Horizon may require visible Meta approval after any request, including
  the opt-in restart request.
- Some installed packages do not expose a public launchable activity and
  therefore cannot appear as launch targets.

## License

AGPL-3.0-or-later. See `LICENSE`.

Meta Spatial SDK and other dependencies retain their own licenses and terms.
See `THIRD_PARTY_NOTICES.md`.
