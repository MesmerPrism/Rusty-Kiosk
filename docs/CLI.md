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

These statements describe the debug ADB adapter. Rusty Kiosk 0.6.0 also has a
separate wearer-enabled release direct link; it is documented in
[`DIRECT_OPERATOR.md`](DIRECT_OPERATOR.md) and uses the same typed queue rather
than the debug activity.

## Release host operator

Release builds expose host schema `rusty.kiosk.host_operator.v4` through a
separate `ContentProvider.call()` adapter for an already authorized ADB shell. Android
requires the caller to hold `android.permission.DUMP`; ordinary headset apps do
not. The provider admits one bounded request into the same app-private queue and
returns only the matching structured result as Base64. The same provider also
transfers the one fixed tag document through ordered 6 KiB Base64 chunks. A
write is capped at 256 KiB, SHA-256 checked, schema validated, and atomically
activated before acknowledgement. It supports no query, insert, update, delete,
shell, component, intent, host-supplied path, endpoint, or free-form setup route.

Provider v4 adds read-only `request-status`, exact queued-request `cancel`, and
explicit `direct-status`, `direct-enable`, and generation-bound
`direct-disable`, plus cleanup-only `direct-recover-disable`. Status never enqueues a command. Requests expire after two
minutes, retain durable pending/claimed identity across process restart, and
end in a bounded exact-ID receipt. Claimed and terminal requests cannot be
cancelled. Lifecycle states are `pending`, `pending_wearer_action`,
`confirmed`, `rejected`, `expired`, `cancelled`, and `unknown`.

`direct-enable` requires a unique operation ID and returns channel, package,
endpoint, bridge generation, session ID, expiry, `enabled_by_request`, and one
standard-Base64 32-byte session secret. It never returns the persistent pairing
code. The exact ADB serial belongs to the desktop wrapper; raw `content call`
output must go directly into a redacted in-memory parser and must never be
echoed, logged, or included in a diagnostic `CommandResult`. `direct-disable`
requires the originating operation/session IDs and expected generation and
fails closed after a generation change.
Enable and disable are asynchronous foreground-service transitions;
`completed` is truthful readback, and the host polls no-argument
`direct-status` until the expected enabled/running pair converges.

The five-minute HMAC secret is purged independently of a bounded 24-hour,
non-secret cleanup tombstone. Disable requires `enabled_by_request=true` and an
atomic current-generation recheck. If output was lost, the DUMP-only recovery
method accepts only the original operation ID; it returns no session or pairing
credential and can only disable or re-dispatch STOP for that owned generation.
Current-generation stopped readback consumes the retry record.

The host sequence is deliberately two-stage: call `invoke`, start the fixed
`.RustyKioskActivity` with the admitted request id, then poll `result`. This
keeps visible action execution in the same Activity handlers as the panel and
avoids granting the provider hidden foreground or business-logic authority.
The public QuestIonAble File Manager implements this contract for its optional
Rusty Kiosk tab.

QuestIonAble File Manager also implements `rusty.kiosk.direct_operator.v2` for
post-bootstrap operation without ADB. Its `kiosk-direct` CLI family covers
status, typed commands, tag import/export, app-owned staging, and attended APK
install receipts. Install admission supplies an ordered strict
`{name, bytes, sha256}` entry for every APK and Kiosk verifies those committed
bytes from the same opened handle copied into PackageInstaller. This is a separate authenticated network transport, not an
expansion of the `DUMP` provider.

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
| `set-launch-requirement` | `any`, `wifi-on`, or `wifi-off` | Change the selected app's dedicated launch requirement |
| `cancel-pending-launch` | none | Cancel the current unmet-requirement launch |
| `launch-normal` / `launch-kiosk` | none | Use the corresponding launch button |
| `check-setup-helper` | none | Refresh the fixed helper's installed/provisioned status |
| `request-wifi-adb` / `disable-wifi-adb` | none | Use the corresponding visible fixed-operation control |
| `enable-wifi-adb-after-boot` / `disable-wifi-adb-after-boot` | none | Turn the visible restart-request preference on or off |
| `enable-accessibility` / `disable-accessibility` | none | Use the same explicit control handlers |
| `passthrough-natural` / `passthrough-contour` | none | Select the same persisted passthrough appearance control |
| `exit-meta-home` | none | Disarm and use the visible Meta Home exit action |

`set-search`, `filter-tag`, and `select` update the same retained browsing state
as the visible search field, tag chips, and app rows. Their values survive the
fresh Spatial task created by a triple-Home return; blank filter values
explicitly clear that state, and an app outside the current results is never
retained in the details area.

Searchable tags and launch requirements are independent. A passive tag named
`wifi-on` has no effect. Both launch commands preflight ordinary Wi-Fi before
target or guard mutation; unmet state opens fixed Android Wi-Fi settings and
returns `pending_wearer_action`. Return revalidates the exact bound app before
launch.

Fixed helper commands write their result only after the helper answers and the
main app performs effective-state readback. A Wi-Fi ADB request can complete as
an app operation while Meta's protected approval remains pending; call `status`
after the wearer responds and require `wifi_adb_enabled` to match the expected
state.

Passthrough commands return the same state receipt as the panel. Acceptance
requires `system_passthrough_enabled=true`; `passthrough_style` reports
`natural` or `contour-lut`, and `passthrough_lut_applied` confirms that the
retained 16³ LUT reached the Spatial SDK scene.

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
The request is resolved through the field's attached virtual-display context,
and its privacy-safe marker reports matching field/IME display IDs, attachment,
window-token presence, attempt, and acceptance state. Horizon grants the Spatial
display its served-view input connection only after a real pointer activation,
so a wearer click remains the keyboard gate. The CLI does not counterfeit that
trusted event with touch injection.

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
