# Optional Shell menu closing

**Shell menu closing** is an explicit developer opt-in in User controls and
`set-kiosk-mode shell`. Accessibility remains the default. The main APK keeps
ordinary application privileges; Launcher Lite and the setup helper do not
acquire this mode. It needs a separately bootstrapped, genuine Android UID/eUID
2000 companion and compatible Horizon AppFunction support. Unavailable or
unresolved state blocks arming without fallback.

The companion has one fixed operation:
`com.oculus.vrshell.appfunctions.ShellAppFunctions#openNavigator`, with empty
parameters. It observes native Navigator open/closed signals, waits 80 ms,
and asks the app to claim that exact candidate before execution. The app owns
target qualification, generation, third-Home escape and disarm policy. This is
a platform-dependent developer path, not managed kiosk, Guardian control,
input interception, remapping, or proof of a visible effect.

## Authority and custody

The companion compiles the unchanged shared Quest `ShellApplicationContext`
factory selected in `vendor/quest-shell-context/UPSTREAM.json`. Genuine UID/eUID,
Application, attribution and AppOps checks remain upstream. The public factory
grants no operation authority. APKs do not bundle it. Stable and Labs builds bind
their fixed Kiosk package/provider/component at compilation; no runtime endpoint
override exists. The app provider admits `call()` only, requires DUMP and the
immediate Binder UID 2000/PID, and accepts a closed typed protocol.

Before returning `execute`, the app commits one durable original bound to
helper session, boot, PID, target, generation and command UUID. Failed commit
grants no execution. App restart restores custody without replay or Shell
rearming. Malformed, null, uncertain or failed storage stays held. New guards
in every mode wait for the original; an existing Visibility controller keeps
its own authority/lifecycle. Shell arming also waits for that controller to retire.

Only the exact original helper may retire its exact command/generation with a
genuine callback or authenticated no-dispatch completion. Retirement commits
before releasing custody. Unknown/error/timeouts disarm Shell and retain the
original. Late callbacks may retire it but never rearm. Mode changes, Normal
launch, returning to Kiosk and Exit stop admission while retaining any original.
No retry, replacement helper or new operation resolves an unknown original.

The startup `isAppFunctionEnabled` read is also owned. Its intent and callback
are separately durable. The 15-second deadline reports pending only; STOP and
session expiry do not cancel it or permit exit. A late callback after STOP
retires the read without starting the reader or registering readiness. FINAL
waits for this read, execution callbacks, app retirement acknowledgements,
provider withdrawal, queued jobs and actual reader EOF/reap. Callback success
still reports `effect_verified=false`.

## Build and attended bootstrap

Source qualification needs no headset:

```powershell
pwsh -NoProfile -File tools/Test-QuestShellContext.ps1
pwsh -NoProfile -File tools/Build-RustyKioskShellCompanion.ps1 -ProductChannel stable
pwsh -NoProfile -File tools/Build-RustyKioskShellCompanion.ps1 -ProductChannel labs
```

Fresh BUILD.json binds source inventory, channel, Quest pin, tool hashes,
artifact hash/bytes and pure tests. Use a clean committed checkout. Host tests
and desktop previews do not qualify firmware or a headset. Follow the owner
device workflow and exclusive custody before attended bootstrap.
`Start-RustyKioskShellCompanion.ps1` requires explicit route, canonical serial,
artifact SHA-256, source ID and channel. It checks these before staging, then
binds a fresh session/boot/process birth. Unknown readiness causes no retry or
cleanup. Select Shell only after fresh readiness; the companion never arms.
ADB may then disconnect.

## Stop, replacement and evidence

First disarm in the app. `Stop-RustyKioskShellCompanion.ps1` checks exact source,
session, canonical device, boot, PID birth and UID before writing STOP.
`STOP_REQUESTED` is not graceful-stop proof. Keep the original caller/evidence
while any read, callback, withdrawal or acknowledgement remains unknown.
Never kill a UI caller or manufacture FINAL to replace an APK.

Before APK replacement or new bootstrap, verify the exact FINAL session, boot,
PID birth, source/channel, resolved calls, capability callback, reader EOF and
released provider. Independently establish absence of both helper and exact
reader process (READY/FINAL contain reader PID/birth), plus ACTIVE absence.
A receipt alone cannot prove process absence. Then replace the APK and perform
a fresh pinned bootstrap.

Postboot quarantine only renames stale ACTIVE after a fresh different boot and
exact source/channel/receipt/journal joins, including every original and the
enabled callback. Same-boot quarantine, unknown originals, deletion and kill
are forbidden. It is not normal retirement and never clears the app ledger.

The owner gate checks the shared inventory, production coordinator/preference
ledger and mode admission, both standalone channel builds/D8 packaging, pure
native lifetime/parser tests, quarantine damage controls and desktop scenarios.
Physical Home behavior and visible effect require separate exact-artifact
hardware evidence.
