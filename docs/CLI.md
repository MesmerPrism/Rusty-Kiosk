# Typed debug CLI

Rusty Kiosk debug builds provide a stable command-line route for exercising
the same application actions as the visible Spatial panel. This replaces
display-coordinate touch injection in automated acceptance runs.

## Boundary

- The exported `.RustyKioskCliActivity` exists only in `app/src/debug`.
- Android requires the sender to hold `android.permission.DUMP`; ADB shell does,
  while ordinary headset applications do not.
- The bridge accepts one typed request at a time and stores it in app-private
  state before bringing `RustyKioskActivity` forward.
- The activity consumes each request once, invokes its existing action handler,
  and atomically writes `files/cli/last-result.json`.
- The debug adapter lets Rusty Kiosk's foreground transition settle before
  invoking a handler. Kiosk launch then uses the same target-scoped five-second
  handoff lease as the visible button. It ignores only trailing Rusty Kiosk
  self-events during the selected target's launch; it does not suppress Meta
  Home, another package, or a genuine Rusty Kiosk resume.
- The host wrapper reads that result through `run-as`, which is available for
  the debuggable APK and does not create a network listener.
- Dispatch does not wait for Android's foreground-transition completion; this
  keeps the CLI responsive while a system-owned Meta consent surface is visible.
  Completion still requires the matching app-private result receipt.
- Text values are carried as bounded UTF-8 Base64 so spaces and punctuation
  survive ADB's remote-shell transport without becoming shell syntax.
- Release builds contain no CLI activity.

The CLI never accepts a shell command, executable path, Android component,
intent action, package to launch, device path, Accessibility gesture, or
free-form setup operation. App selection is restricted to the current visible
catalogue, and launch commands operate only on that selection.

## Usage

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-RustyKioskCli.ps1 `
  -Serial <quest-serial> `
  -Command status
```

Commands with values use `-Value`:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-RustyKioskCli.ps1 `
  -Serial <quest-serial> `
  -Command set-search `
  -Value browser

pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-RustyKioskCli.ps1 `
  -Serial <quest-serial> `
  -Command select `
  -Value Browser
```

## Command vocabulary

| Command | Value | Equivalent app action |
| --- | --- | --- |
| `status` | none | Refresh and report current app/control state |
| `show-controls` / `show-apps` | none | Switch the one panel between its two surfaces |
| `reload` | none | Press **Reload** |
| `focus-search` | none | Focus the native search field and request the Meta keyboard |
| `focus-tag-editor` | none | Focus the native tag field and request the Meta keyboard |
| `set-search` | optional text | Change the search query; blank clears it |
| `select` | visible key, exact package, or exact label | Select a visible catalogue row |
| `filter-tag` | optional tag | Select a tag chip; blank selects **All apps** |
| `add-tag` / `remove-tag` | tag | Use the selected app's tag action |
| `launch-normal` / `launch-kiosk` | none | Use the corresponding launch button |
| `check-setup-helper` | none | Refresh the fixed helper's installed/provisioned status |
| `request-wifi-adb` / `disable-wifi-adb` | none | Use the corresponding visible fixed-operation control |
| `enable-wifi-adb-after-boot` / `disable-wifi-adb-after-boot` | none | Turn the visible restart-request preference on or off |
| `enable-accessibility` / `disable-accessibility` | none | Use the same explicit control handlers |
| `exit-meta-home` | none | Disarm and use the visible Meta Home exit action |

Fixed helper commands write their result only after the helper answers and the
main app performs effective-state readback. A Wi-Fi ADB request can complete as
an app operation while Meta's protected approval remains pending; call `status`
after the wearer responds and require `wifi_adb_enabled` to match the expected
state.

For the system-owned Home transition used by soft-guard testing, use the
separate typed wrapper:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-RustyKioskHome.ps1 `
  -Serial <quest-serial>
```

That wrapper launches Android's resolved `MAIN` + `HOME` activity and returns
the resolved component plus bounded resumed-activity readback. It does not
inject a tap or key event, and it does not run Rusty Kiosk's CLI activity,
which would itself bring the kiosk forward and contaminate an armed-target
test.

The intentional three-Home escape window can be exercised without host setup
overhead between transitions:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-RustyKioskHome.ps1 `
  -Serial <quest-serial> `
  -Count 3 `
  -IntervalMilliseconds 1400
```

## What it does not prove

The CLI proves the application's action routing and state projection. It does
not emulate a Touch controller, approve Android or Meta protected prompts, or
replace a physical Meta-button witness. The focus commands route to the native
text fields and issue the same bounded IME request as their click handlers.
Horizon grants the Spatial display its served-view input connection only after
a real pointer activation, so a wearer click remains the keyboard gate. The CLI
does not counterfeit that trusted event with touch injection.

## Exact watchdog transition CLI

Android's generic `MAIN + HOME` activity can emit more than one Horizon window
signal and is therefore useful system-integration evidence, not a deterministic
one-command/one-press state-machine driver. Debug builds also expose a separate
`DUMP`-protected receiver that applies exactly one reviewed Home transition to
the active watchdog without bringing Rusty Kiosk forward:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-RustyKioskGuardCli.ps1 `
  -Serial <quest-serial> `
  -Count 3 `
  -IntervalMilliseconds 1400
```

The first two receipts must report `schedule_recovery` with the guard still
armed; the third must report `disarm_and_return` with the guard off. The receiver
and its manifest entry exist only in `src/debug`, accept no value or intent
choice, and write a bounded app-private receipt. This proves watchdog routing;
it does not open Meta Home or replace a physical Meta-button witness.
