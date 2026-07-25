# Rusty Kiosk Launcher

## Decision

`launcher` is a separate native 2D Android application for private Meta release
channels. Its complete product job is:

1. inspect the one expected Rusty Kiosk package;
2. require the pinned official Rusty Kiosk release signing certificate;
3. open the package's normal Android front door when both checks pass; or
4. show the official installation and release locations when the target is
   missing or needs repair.

It is not part of the Rusty Kiosk APK and does not share Kiosk runtime
authority.

## Packages

| Surface | Package |
| --- | --- |
| Native 2D launcher | `io.github.mesmerprism.rustykiosk.launcher` |
| Sideloaded Rusty Kiosk target | `io.github.mesmerprism.rustykiosk` |

The launcher manifest declares one exact `<queries><package ... /></queries>`
entry. It does not use `QUERY_ALL_PACKAGES`.

## Trust and launch flow

The launcher reads the SHA-256 certificate digest from the checked-in,
provenance-bound copy of the published Rusty Kiosk v0.6.4 bundle manifest under
`launcher/trust/`. Android `SigningInfo` readback may match either the sole
current signer or its Android-validated signing history. A package with
multiple current signers fails closed. Package-name presence without the
trusted certificate is a repair state and is never launched.

```text
package absent --------------------------> show install guidance
package present + wrong signer ----------> show repair guidance
trusted package + no launch activity ----> show repair guidance
trusted package + normal front door -----> start target, remove launcher task
```

The Activity receives no target package, component, signer, or URL through an
Intent. It cannot be redirected to another package.

## Authority boundary

The launcher has:

- one exported Activity;
- one exact package query;
- two fixed HTTPS links;
- Android package/signing readback;
- one normal target launch request.

It has no declared permissions, Spatial SDK dependency, native library,
service, provider, receiver, package installer, Accessibility component,
network listener, storage access, analytics, account integration, or update
logic. Opening a fixed HTTPS link delegates to the user's browser; the launcher
itself does not perform network requests.

Rusty Kiosk remains responsible for its panel, catalogue, soft-kiosk behavior,
setup helper, direct link, attended local installer, and runtime health.
Android and Horizon OS own task placement and foreground completion. Meta
release channels own launcher distribution and tester entitlement.

## Build and release signing

Debug build:

```powershell
.\gradlew.bat :launcher:testDebugUnitTest :launcher:lintDebug :launcher:assembleDebug
```

Initialize the launcher's independent release identity once:

```powershell
pwsh -NoProfile -File .\tools\Initialize-RustyKioskLauncherReleaseSigning.ps1
```

The generated keystore and environment loader stay under ignored
`local-artifacts/`. Dot-source the loader, then prepare a signed Alpha
candidate:

```powershell
. .\local-artifacts\signing\rusty-kiosk-launcher\Load-RustyKioskLauncherReleaseSigning.ps1
pwsh -NoProfile -File .\tools\Prepare-RustyKioskLauncherAlphaCandidate.ps1
```

An upload candidate must come from a clean exact commit, retain the production
package and target identities, contain no permissions or background
components, pass the 2D Meta manifest checks, and have a verified signature.
Alpha distribution does not authorize Production or public Store submission.

## Validation

Host policy tests cover missing, signer-mismatch, missing-front-door, and ready
decisions plus current signer, signing-history rotation, multiple-signer,
empty/null, and malformed-digest cases. The wrong-signer and trusted-target
headset cases exercise Android's real `SigningInfo` path. APK inspection
additionally checks:

- package, label, version, install location, and launch Activity;
- `MAIN`, `LAUNCHER`, and `com.oculus.intent.category.2D`;
- required head-tracking feature and supported-device metadata;
- release `debuggable=false` and `excludeFromRecents=true`;
- no declared permissions, native libraries, or background components;
- one exact Rusty Kiosk package query;
- verified release signer and APK SHA-256.

Repository validation also binds the build-time signer pin to the public
v0.6.4 bundle manifest, its upstream asset digest and URL, source revision,
published APK digest, and signer digest.

Device validation keeps three results distinct:

- missing target: launcher remains visible with installation guidance;
- wrong signer: launcher remains visible with repair guidance;
- trusted release target: Rusty Kiosk becomes the effective foreground app and
  the launcher task is removed.

The first two states can use purpose-built debug variants without uninstalling
or replacing an existing Rusty Kiosk installation. Meta-installed proof remains
separate from sideloaded debug proof. Every run pulls the installed launcher
APK and records its hash, signer, version, and Android install-source fields.
The post-Alpha run additionally requires the expected candidate hash and signer
plus a non-shell initiating package.
