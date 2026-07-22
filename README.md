# Rusty Kiosk

Rusty Kiosk is a small public Meta Quest launcher example built with Meta
Spatial SDK. It renders one native Android panel over system passthrough,
browses launchable apps installed on the headset, assigns searchable tags, and
offers normal or soft-kiosk launch.

New-user setup and behavior are explained on the
[Rusty Kiosk onboarding site](https://mesmerprism.com/Rusty-Kiosk/).

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
- Spatial SDK-owned system passthrough with no room model or skybox, natural
  color by default, and an optional contour-revealing color LUT;
- launchable-app discovery for ordinary Android, 2D, Leanback, and Quest VR
  front doors;
- installed-app search by label, package, or tag;
- tag filtering;
- search text, active tag filter, and selected visible app retained across
  fresh Kiosk returns until the wearer changes or clears them;
- tag editing from the panel;
- hot reload of an externally editable JSON tag file;
- unresolved name-only entries shown as **Not installed**;
- normal and soft-kiosk launch actions;
- package/window-only Accessibility monitoring with UI retrieval disabled;
- an always-visible status strip for passthrough, the direct link, and
  Accessibility;
- an explicit, reversible user-control center backed by a dedicated fixed-operation setup helper;
- native Android text inputs using an explicit Quest keyboard path;
- a typed, ADB-shell-protected debug CLI for stable wearer-equivalent testing.
- a release-safe, `DUMP`-protected typed host adapter for optional desktop
  management through Meta Quest File Manager.
- an explicitly wearer-enabled local PC link for the same typed commands, tag
  file, bounded app-owned staging, and Android-confirmed APK sessions without
  routine ADB.

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

Tagged public releases build and verify a same-signer release pair, then attach
the two APKs, their hashes/source manifest, the AGPL license, and source pointer
using the stable filenames consumed by Meta Quest File Manager. Release signing
material stays in GitHub Actions secrets and is never committed.

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

Wi-Fi ADB, Accessibility, the direct PC link, and local APK installation are
independent opt-ins. Rusty Kiosk does not enable any of them automatically.
The main APK never holds `WRITE_SECURE_SETTINGS`, and neither APK contains a
shell, terminal UI, raw command surface, or arbitrary intent/path bridge. The
setup helper remains network-free.

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
- enable or disable the authenticated direct PC link and rotate its pairing code;
- open Android's visible per-app permission for local APK installation;
- disable Wi-Fi ADB again;
- exit directly to Meta Home.

The Accessibility service can disable itself even when the setup helper is
unavailable. Once the helper has been provisioned, Rusty Kiosk can request
Wi-Fi ADB again later even when the previous transport is off. Horizon OS
still owns the approval UI; the app and CLI cannot approve it.

See [Transparent user controls](docs/USER_CONTROL.md) for setup, status,
reboot recovery, privacy, and complete revocation instructions.

## Direct PC link without ADB

After the one-time installation/provisioning step, routine Kiosk commands,
tags, bounded file staging, and wearer-confirmed APK installation can use the
local direct link instead of USB or Wi-Fi ADB. Enable it in **User controls**,
then enter the displayed `http://` address and pairing code in Meta Quest File
Manager. The pairing code is generated on-headset and can be rotated locally.

Meta Quest File Manager uses its PC ADB installer as the default APK route once
that PC is authorized. It supports unattended and batch installation without an
in-headset decision for every package. The direct local installer is an
attended fallback: allowing Rusty Kiosk as an install source is a one-time
grant, but Android can still request one confirmation for each app installation
session. A base APK and its splits are combined into one session.

The direct protocol accepts only fixed endpoints. Requests expire, replay IDs
are retained, request bodies are SHA-256 checked, and requests and responses
are HMAC-SHA-256 signed. It is authenticated and integrity-protected, but the
current HTTP transport is not encrypted; use a trusted local network or the
PC's private hotspot. See [Direct operator link](docs/DIRECT_OPERATOR.md).

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
- Local PackageInstaller sessions cannot silently approve themselves. Android
  and Horizon own the per-app installer permission and the install/cancel UI.
- Direct file operations are intentionally confined to Rusty Kiosk's app-owned
  staging area. General shell-visible filesystem browsing remains an optional
  ADB function in Meta Quest File Manager.

## License

AGPL-3.0-or-later. See `LICENSE`.

Meta Spatial SDK and other dependencies retain their own licenses and terms.
See `THIRD_PARTY_NOTICES.md`.
