# Visibility menu closing

Visibility menu closing is an explicit optional soft-kiosk mode. It runs in the
normal Rusty Kiosk app and needs compatible Horizon OS plus the wearer-enabled
Accessibility service. It does not need the shell companion, ADB after setup,
or an extra Android permission grant. Accessibility recovery remains the
default; selecting this mode never enables a service or substitutes another
mode when support is missing.

## Selecting and stopping

Select an installed app, choose **Visibility menu closing**, then use the same
**Kiosk launch** action. Initial launch creates a fresh selected-app task;
normal launch disarms the guard and may resume the existing task. The panel
disables the Visibility choice on unsupported headsets and explains readiness.
The typed equivalent is `set-kiosk-mode` with value `visibility`; an unavailable
selection can be retained, but cannot launch an armed guard.

Normal launch, changing mode, returning to Rusty Kiosk, explicit exit, another
app becoming foreground, or loss of the Accessibility service disarms this
mode. A new explicit kiosk launch is required to arm again. No guard operates
on Rusty Kiosk itself.

## What causes a close

The app subscribes only to Horizon's fixed Navigator visibility signal through
the runtime CRCS service. Accessibility metadata qualifies the exact launched
target and detects departures; it never synthesizes a visibility event or
issues a second recovery action. Initial TRUE is observational. A FALSE
baseline and fresh selected-target qualification are required before a changed
FALSE-to-TRUE callback can admit one fixed Navigator broadcast.

The first two distinct admitted visibility openings within five seconds can
close the menu. The third within an inclusive five-second window persists
disarm before another close and returns to Rusty Kiosk. Returning to the app or
observing FALSE does not clear this rolling count. Visibility edges already
deduplicate repeated TRUE callbacks, so this mode has no 1.2-second Home debounce.
These are platform visibility episodes, not physical button measurements.

After a known transport return, a FALSE callback followed by 160 ms of
callback quiet permits another opening. The selected target remains qualified
for that armed generation; this quiet period does not establish current window
focus or disappearance of pixels. Target-package panel metadata cannot qualify
a target or be mistaken for another app. A new opening while the previous
effect is unresolved disarms without a queued toggle or retry.

## Lifetime and failures

The guard remains target- and generation-bound until a normal lifecycle action,
escape or failure ends it. Prepared one-use conditional tickets are replenished
after known sends; their ring size is not a guard lifetime or action-count limit.
Cold preparation is durable before registration. A known transport return ends
original custody immediately. Reporting runs on a separate worker, and slow or
failed reporting displays a warning without blocking false-quiet settling,
escape or platform retirement. Remaining durably prepared tickets can be used;
a replacement becomes executable only after its own durable acknowledgement.
An uncertain transport outcome retains that original and blocks new admission.
Tickets are never restored or replayed after process restart. Registration and
unregistration use the same controller instance; readiness waits for known
platform retirement, independently of queued reporting.

Registration taking more than thirty seconds is an advisory pending observation;
it never cancels, retries or turns a healthy original into an unknown outcome.
Selected-app startup has no estimated-duration expiry. Fresh exact-target
metadata can qualify a slow launch while its live generation remains armed.
Per-event freshness and actual departure/disarm boundaries still apply. The
separate five-second trailing-Kiosk-event lease is not a startup deadline.

The UI and CLI distinguish compatible platform support from current readiness,
registration, health, armed generation and failure. An unsupported framework,
disconnected service, stale generation or held original has no automatic
Accessibility or shell fallback. Selecting another mode is an explicit action.

## Timing and evidence

Signal callback receipt uses Android uptime. Vendor sample timestamps, native
menu OPEN/CLOSED observations, Accessibility metadata, app focus and visible
presentation remain separate endpoints. Broadcast return proves a normal app
call returned; it does not prove delivery or visual closure. Menu openings can
still be briefly visible. Neither this mode nor the timing summary promises
zero flashing, physical Meta-button interception, or a managed-device lock.

Compare modes with the same target and independent visual checks. Preserve
missed signals, unknown operations, reopenings and visible-menu failures rather
than interpreting a logical FALSE or focused app as a visual pass.

## Shared observer source and adoption

Kiosk consumes the unchanged Quest component at
`eff10990c0eb73dbb4daf223d74fef33bae4e1bb`, selected by the closed source/license
inventory in `vendor/quest-navigator-visibility/UPSTREAM.json`. The small
`navigator-visibility` Android build facade compiles vendor signatures separately
and includes only runtime component classes. Its own empty manifest lets AGP
supply namespace/minSdk while preserving upstream manifest bytes. The module-local
WrongConstant lint exception covers the fixed `crcs` service name omitted from
Android's public service-name StringDef; other app/library lint remains enabled.
No copied Kiosk framework reader or independent component implementation remains.

Availability means vendor service/linkage presence, not registration success,
fresh signal delivery or useful menu effect. The actual subscription explicitly
starts after Kiosk has durably prepared its tickets. Observer generation/sequence
is separate from the app's cryptographic guard generation. Equal receipt uptime
is allowed only with a newer source sequence. A true callback already superseded
on the callback thread is observational and cannot count an opening or send a
late toggle. The library preserves all ordered values in vendor batches.

Both User controls and `set-kiosk-mode` use `setKioskMode`, which disarms and
cancels a pending requirement launch before selecting another mode. Existing
ordinary-Wi-Fi preflight, app launch options, provider-v4 request lifecycle,
Stable/Labs identity and standalone Lite remain separate. This port includes
Visibility only; it does not transplant the independent Shell mode.

## Coordinated integration evidence

Source/host gates do not establish Android ART, real CRCS delivery, current
firmware permission, rendered pixels, performance or distribution eligibility.
Use one exact source-built app APK when a device/build window is coordinated.
Reuse compatible installed package data, setup-helper grant, Accessibility
selection and target app after fresh readback. An unchanged old APK cannot prove
this library adoption. Do not reset data or reinstall unrelated packages for the
comparison.

The actual capsule should bind source/vendor inventory, APK hash/signer/package,
current serial/boot/firmware, service binding and selected mode. Check initial true
without false baseline; false plus exact target qualification; repeated independent
open/close episodes; inclusive five-second third-edge escape; explicit normal
launch/mode change/service loss; old-generation and pending-original retirement;
and ordinary Home from the returned Kiosk panel. Preserve typed receipts and
unknown outcomes. A signal false or broadcast return cannot replace independent
wearer/pixel observation. Device work and release/Store review remain separate.
