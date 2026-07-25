# Validation

## Host gate

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\check_repo.ps1
```

The gate checks public-boundary terms, both APK manifests, Kotlin unit tests,
browser/native panel contracts, Android lint, release CLI exclusion, and debug
assembly for both APKs. It also checks the separate native 2D launcher policy,
manifest, Java tests, lint, and debug assembly.
The same complete gate runs in GitHub Actions for every pull request and push
to `main`; release publication additionally rebuilds and verifies the signed
release pair.

Unit tests cover:

- tag-file parsing and normalization;
- package-first and name-only catalogue matching;
- missing name-only entries remaining visible under their tags;
- search plus tag filtering;
- retained search, tag-filter, and visible-selection restoration plus explicit
  filter clearing;
- normal launch disarming before launch;
- normal launch retaining resumable-task flags while initial kiosk launch uses
  a fresh-task policy;
- visible and typed kiosk launch handoff ignoring only a matching, bounded
  stale Rusty Kiosk foreground event;
- first/second Home recovery and third-Home return;
- Home debounce and five-second escape-window reset;
- generic and exact Horizon signals from one Home press counting only once;
- launch requests remaining provisional until a target-package event confirms focus;
- late Meta-shell tails requesting a bounded, minimum-spaced recovery burst without
  advancing the Home escape count;
- fixed setup-helper result parsing and fail-closed request matching;
- exact-component Accessibility enable/disable list construction;
- preservation of other enabled Accessibility services;
- natural identity and contour-band passthrough LUT mapping;
- typed CLI parsing, payload bounds, value rules, and unknown-command rejection.
- launcher missing-package, wrong-signer, missing-front-door, and trusted-ready
  decisions;
- deterministic lowercase SHA-256 certificate digest formatting.

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
The release host-operator provider is checked separately: it must remain
`DUMP`-protected, expose only `ContentProvider.call()`, reuse the bounded typed
protocol and one-request queue, and return only the matching structured
receipt. Release builds must still exclude both debug components.

To validate the same-signer release asset path without using production
credentials, run:

```powershell
pwsh -NoProfile -File .\tools\Test-ReleasePipeline.ps1
```

The test generates a one-day local key under ignored `artifacts/`, builds both
release APKs with that key, verifies their certificate digests match, stages the
five-file public bundle contract, and removes the temporary key and bundle.

The standard host gate runs the interactive browser model and verifies that
the production native panel, shared geometry/control contract, browser
projection, and source-bound native host remain synchronized. Run the deeper
native visual gate separately when panel visuals change:

```powershell
pwsh -NoProfile -File .\tools\Test-RustyKioskPanelPreview.ps1 -RenderNative
```

That produces ignored source-bound images; it does not replace the headset
gate below.

The gate also requires the Meta passthrough capability and both Quest keyboard
capabilities in the main manifest:
`com.oculus.feature.PASSTHROUGH`,
`com.oculus.feature.VIRTUAL_KEYBOARD` and
`oculus.software.overlay_keyboard`.

The launcher static gate separately requires one native 2D Activity, one exact
Rusty Kiosk package query, the provenance-bound public v0.6.4 bundle manifest,
fixed official install links, and no declared permissions or background
components. The launcher reads its signer pin from that checked-in manifest;
the gate binds the fixture to its public release URL, upstream manifest digest,
source revision, APK digest, and signer digest. The release-candidate gate
performs final APK badging, manifest, signature, and hash inspection.

## Native 2D launcher headset gate

Use `tools/Invoke-RustyKioskLauncherSmoke.ps1` only with an explicit serial and
an already reserved headset. Keep the three cases separate:

1. A missing-target debug variant stays visible and logs `state=missing`.
2. A device with the expected package under a different signer stays visible
   and logs `state=signer-mismatch`.
3. A device with the official release signer logs `state=trusted-launch`,
   brings Rusty Kiosk forward, and removes the launcher task.

Every run pulls the installed launcher APK and records its hash, signer,
version, installer, initiating/originating package, package source, package and
Activity, foreground before/after, target version, expected marker, bounded
fatal count, and whether restoring the pre-run launcher-package presence
requires an explicit uninstall. A sideload run also requires the installed APK
hash to match its input. Do not uninstall automatically.

After Meta Alpha upload, repeat the trusted-target case with
`-SkipInstall`, the candidate values supplied through
`-ExpectedLauncherApkSha256` and `-ExpectedLauncherSignerSha256`, and
`-RequireNonShellInstallSource`. The run passes only when the installed bits,
signer, and non-shell install source match as well as the launch behavior. That
receipt proves the Meta-distributed package launched; it does not replace the
local missing/wrong-signer policy evidence.

## Headset gate

Device validation is intentionally separate from source validation. Use an
explicit Quest serial and the public Meta Quest workflow. A complete attended
run should prove:

1. the app opens one panel over natural passthrough with no room or skybox;
2. **User controls** reports `Natural`, switching to **Contour LUT** visibly
   applies hard color bands, and switching back restores natural color;
3. at least one sideloaded and one Meta-distributed launchable app are listed;
4. an externally edited tag file reloads without restarting the app;
5. a name-only missing entry appears and is labeled not installed;
6. normal launch leaves the watchdog disarmed;
7. kiosk launch restores the target after Home #1 and Home #2;
8. Home #3 within five seconds returns to a fresh Rusty Kiosk panel while the
   previous search text, active tag filter, and visible app selection remain;
9. one Home press from Rusty Kiosk reaches Meta Home;
10. no Rusty Kiosk or target-package fatal occurs in the bounded log window.
11. both native Android text fields open the Meta keyboard on a normal
    wearer click and accept text;
12. the status strip distinguishes passthrough, Accessibility, and the direct link;
13. the setup helper is unavailable before installation, reports **Needs USB-C
    setup** before its grant, and reports **Ready** only after serial-scoped
    provisioning;
14. Accessibility can be enabled and disabled through the fixed helper while
    every other enabled Accessibility service is preserved;
15. disabling Wi-Fi ADB leaves Accessibility unchanged;
16. the restart request is off by default, can be enabled and disabled, and
    causes a new request only when enabled;
17. after restart or a later manual request, Meta approval remains visible and
    attended; the panel reports only effective setting state;
18. **Exit to Meta Home** disarms pending guard state and opens Meta Home.

Run app actions through `tools/Invoke-RustyKioskCli.ps1`; display-coordinate
touch injection is not accepted. `focus-search` and `focus-tag-editor` must
route to the production native fields and produce an app marker showing the
explicit IME request was issued without logging text. The marker must show the
same nonzero `fieldDisplayId` and `imeContextDisplayId`, an attached field, and
a present window token. The source gate also requires the activity's
`FLAG_ALT_FOCUSABLE_IM` keyboard-compositor compatibility flag. A normal wearer
click is still required for Horizon to
grant the Spatial display its served-view input connection and visually show
the keyboard.
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
