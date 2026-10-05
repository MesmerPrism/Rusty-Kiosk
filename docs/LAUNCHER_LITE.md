# Launcher Lite hybrid update candidate

[Rusty Launcher Lite](https://www.meta.com/en-gb/experiences/rusty-launcher-lite/1241943475671333/)
is a published standalone Meta Store app. On 2026-10-05 its public listing
reported version `0.2.0`, released 6 August 2026, with the existing 2D app-library
description. Its canonical privacy policy is
[the published privacy page](https://mesmerprism.com/privacy/rusty-launcher-lite/). The source now
includes its existing Lite module on current full-Kiosk main; full Kiosk and
its Stable/Labs channels remain separate products. Candidate `0.3.0` / code `3`
preserves the published Store package and reviewed launcher signer policy.
Known local signed predecessor evidence is `0.2.0` / code `2`; current installed
headsets also report that version. On 2026-10-05 the authenticated publisher
console confirmed approved production `0.2.0` / code `2`; its inspected build
list contained codes `1` and `2`, making code `3` the next candidate. This candidate is not yet published or
device qualified.

The original `RustyLauncherLiteActivity` remains the default window front door.
`RustyLauncherLiteSpatialActivity` provides immersive presentation using Meta
Spatial SDK. Both hosts bind one `LitePanelController` and native layout, local
catalogue/search/favorites/tags and unchanged preference storage. Presentation
switches cancel pending Wi-Fi remediation; returning from Settings revalidates
the selected installed target before launch. App-declared options are bounded
read-only capabilities; dispatch rechecks identity and sends only the fixed
opaque option-ID extra to the existing admitted front door.

Lite reads ordinary Wi-Fi state; it never changes Wi-Fi or requests ADB.
Only the closed Spatial SDK permission set is added for immersive display.
There is no helper, Accessibility service, shell/operator provider, Internet,
APK installer, boot receiver, foreground service, account or telemetry.
No companion Kiosk installation or developer provisioning is required.
The same-package upgrade preserves data; uninstall/data clear is not an upgrade.
Spatial SDK introduces native libraries and a substantially larger download
than the original 2D-only APK. Record the actual signed candidate size; the
Lite name describes the product scope, not the original tiny binary size.

## Candidate assembly and verification

Run the repository gate before assembly. The release builder with
`-Distribution Store` selects Lite, while Labs Store and Business select their
existing fixed-target `launcher` builds. The signer policy is checked against
the actual APK, and `Prepare-RustyLauncherLiteStoreCandidate.ps1` binds the clean
source commit/tree and versioned signed artifact. Existing assets are never
overwritten. Store submission is separate from candidate preparation.

Before Store upload, verify actual package/signer/version continuity and test
both presentation hosts on Quest: native keyboard, catalogue/search/filter
restoration, mode-switch cancellation, settings return, option dispatch drift,
normal app launch/return and system Home escape. Store screenshots must come
from the exact signed production candidate and identify their actual capture
surface; a flat Activity render does not prove immersive compositor appearance.

## Proposed Store copy after validation

Rusty Launcher Lite is a standalone app library for Meta Quest. Find installed
apps by name, package or tag, keep favorites, and open an app's available launch
options. Use the same library in a normal window or an immersive panel.
Optional Wi-Fi preferences explain mismatches and open standard settings;
Lite never changes Wi-Fi. No companion app, Developer Mode, ADB, PC, account
or subscription is required.

This is proposed copy for the validated hybrid update, not a statement that
the current public 0.2.0 listing already offers immersive mode. Review the
canonical privacy policy for the SDK permission/display changes before
submission; adding Spatial SDK does not itself establish new data collection.

The closed visibility queries contain four catalogue front-door categories and
one exact `MAIN` + `HOME` navigation query, plus the optional full-Kiosk package.
The Home query lets the hybrid navigator resolve an enabled exported system
Home Activity and send the fixed window-return PendingIntent to that component.
It does not add Home as a catalogue category or declare Lite as a Home handler.
The immersive host has its own task affinity, so removing the outgoing task
cannot remove the incoming presentation task.

## SDK internal service

The `meta-spatial-sdk-isdk:0.13.2` AAR contributes
`com.meta.spatial.channels.ChannelBrokerService` with `exported=false` and no
intent filters or permissions. Its inspected SDK channel implementation binds
that exact component with `BIND_AUTO_CREATE`; Binder/Messenger methods register,
look up and unregister internal channels and track Binder death. This is SDK
plumbing, not a Lite operator or boot service. No official evidence established
that removing it is safe for the supported SDK path, so the merged-manifest and
APK guards allow only this exact declaration and reject any added attributes,
filters, receivers, providers or other services. Runtime activation/lifetime
remains part of candidate validation, not a claim inferred from the manifest.
