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
- protocol-v2 foreground-loss signals remaining advisory and sharing the
  generation-bound bounded recovery episode without advancing Triple-Home;
- exclusive Binder UID/package, launch/call metadata, signing-lineage
  continuity, package-installation identity, multi-signer rejection, and stale
  re-arm rejection;
- nonzero collision-resistant guard generation allocation;
- launch requests remaining provisional until a target-package event confirms focus;
- late Meta-shell tails requesting a bounded, minimum-spaced recovery burst without
  advancing the Home escape count;
- fixed setup-helper result parsing and fail-closed request matching;
- exact-component Accessibility enable/disable list construction;
- preservation of other enabled Accessibility services;
- natural identity and contour-band passthrough LUT mapping;
- typed CLI parsing, payload bounds, value rules, and unknown-command rejection.
- strict legacy/v2 launch-requirement migration; passive-tag independence;
  conflict/unknown rejection; both-mode Wi-Fi preflight; cancellation, expiry,
  settings-return debounce, process-restart cancellation, app disappearance,
  and point-of-use target/install/document revalidation;
- provider-v4 process-wide transition locking, original-expiry transfer,
  first-terminal tombstones, concurrent enqueue, cancel/consume, and
  expiry/record races;
- Direct USB session entropy, one-time operation IDs, issuance rate/concurrency
  bounds, monotonic-wall-clock issuance, capability/generation/expiry/revocation
  rules, raw-byte HMAC, generation-bound crossed START/STOP rejection, long-run
  non-secret cleanup ownership, lost-response STOP recovery, immutable direct
  install byte commitments, and the checked Kiosk/QFM bootstrap wire fixture;
- launcher missing-package, wrong-signer, missing-front-door, and trusted-ready
  decisions;
- deterministic lowercase SHA-256 certificate digest formatting.

The static guard checks additionally require Rusty Kiosk to disarm itself when
its own package becomes foreground and prohibit Accessibility UI-tree access,
global actions, gestures, and Android HOME-role declarations.
They require the exported foreground provider to remain call-only, v2-only,
and bound to the armed package, exclusive UID, protocol metadata, launch-time
signing lineage, installation/update identity, and current readback. The client
module must remain engine-neutral and the main Kiosk application must not
advertise itself as a client.
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
six-file public Labs release inventory, and removes the temporary key and
bundle. The sixth asset, `rusty-kiosk-labs-owner-release.json`, has the exact
`rusty.kiosk.labs_release_owner_metadata.v2` schema and explicitly identifies
`rusty-kiosk.apk` as the `complete-product` primary artifact. It hash-binds the
exact closed-shape bundle manifest and its co-installable identity mode, package,
signer, version name/code, and isolated uninstall exit policy. Its strict validator
rejects every wrong or missing authority field and all expanded nested shapes,
then cross-checks the APK hash/bytes and manifest evidence. It also verifies
the closed stable/Labs product channels and alpha-maturity tag grammar, alpha
ordinal and version-code boundaries, unchanged legacy build defaults, exact
APK package/version identities, wrong-channel/version/signer rejection, and
byte-identical manifest restaging. It then builds and inspects a numeric stable
candidate, including suffix 99, unchanged package identities, common signer,
stable product-channel metadata, and consumer-compatible filenames. This is synthetic
pipeline evidence, not production-signer or GitHub-publication evidence.

## Stable and Labs publication gates

Stable publication accepts only an existing exact `vX.Y.Z` tag. Initial Labs
publication accepts only an existing exact `vX.Y.Z-alpha.N` tag with `N` from
1 through 98, publishes it as a prerelease, and verifies that it did not become
the repository's latest release. Both routes bind the checked-out commit and
tree, inspect the two APK package/version/code identities, compare their common
signer to the public Kiosk signer trust anchor, create a previously absent
release, and read back the closed asset set, byte sizes, and GitHub SHA-256
digests. Both workflows accept trigger values only through environment data,
require the exact tag commit to be reachable from freshly fetched `main`,
reject tracked or untracked checkout dirt before signing and staging, avoid
shared Gradle caches, and enter their distinct protected release environments.
Both enumerate authenticated drafts before creation so a prior failed same-tag
attempt cannot be duplicated. Stable reads the exact remote tag peel, commit,
and tree immediately before and after publication. Labs uses a draft-first boundary: the
exact six assets and release
identity are read back before promotion, and all six draft URLs must share one
bounded `untagged-<20 lowercase hex>` GitHub route derived from the release's
exact HTML route. The same evidence is then
read back from the live prerelease with exact final tag URLs. Release ID, asset
IDs, target commit, the bounded tag peel, source commit, and source tree must
remain unchanged across promotion. Any
failed draft or live release is preserved for explicit owner incident handling;
the workflow never deletes or replaces same-tag evidence. The Labs route
accepts only an authoritative no-latest 404 or a different canonical stable
latest tag.

Both workflows read the authorized signer only from
`release/kiosk-release-signer-policy.v1.json`. The checked-in v0.6.4 release
manifest URL and digest establish that policy's provenance. A signer change is
a separately reviewed policy revision, never a tag, dispatch, APK, or staging
input. Manual dispatch is a recovery path for an existing exact tag:
`gh workflow run <workflow> --ref <tag> -f version=<version>`. A default-branch
dispatch is expected to fail.

Labs uses distinct core, helper, permission/action, provider-authority, and
Store-launcher identities. A device gate must prove stable and Labs can be
installed together, each launcher opens only its matching core, wrong signers
fail closed, and uninstalling Labs leaves stable unchanged. Alpha ordinals are
maturity/version-code evidence only; they do not define the product channel.

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
components. It resolves both the exact Store and Business package identities
and rejects unknown or differently cased distribution selectors. The launcher
reads its signer pin from that checked-in manifest; the gate binds the fixture
to its public release URL, upstream manifest digest, source revision, APK
digest, and signer digest. Each release-candidate gate performs exact APK
package and query parsing plus manifest, signature, and hash inspection.

## Native 2D launcher headset gate

Use `tools/Invoke-RustyKioskLauncherSmoke.ps1` only with an explicit serial and
an already reserved headset. Keep the three cases separate:

1. A missing-target debug variant stays visible and logs `state=missing`.
2. A device with the expected package under a different signer stays visible
   and logs `state=signer-mismatch`.
3. A device with the official release signer logs `state=trusted-launch`,
   brings Rusty Kiosk forward, and removes the launcher task.

Every run pulls the installed launcher APK and records its selected package,
hash, signer, version, installer, initiating/originating package, package
source, package and Activity, foreground before/after, target version, expected
marker, bounded fatal count, and whether restoring the pre-run
launcher-package presence requires an explicit uninstall. A sideload run also
requires the installed APK hash to match its input. Do not uninstall
automatically.

After Meta Alpha or managed Business installation, repeat the trusted-target
case with
`-SkipInstall`, the candidate values supplied through
`-ExpectedLauncherApkSha256` and `-ExpectedLauncherSignerSha256`, and
`-RequireNonShellInstallSource`. The Business run must also pass its exact
package through `-LauncherPackage`. The run passes only when the installed
bits, signer, package, and non-shell install source match as well as the launch
behavior. The two release packages must co-install without replacing one
another. These receipts prove the selected Meta-distributed packages launched;
they do not replace the local missing/wrong-signer policy evidence.

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
19. set each selected-app requirement through both visible and typed controls;
    prove a passive `wifi-on` tag alone has no effect;
20. for both Normal and Kiosk modes, prove matching ordinary Wi-Fi launches,
    mismatch opens fixed Android Wi-Fi settings without target/guard mutation,
    unchanged return waits without reopening, changed return launches once, and
    cancel/expiry/app update prevents launch;
21. bootstrap Direct Link through the exact-serial provider-v4 host route,
    retain the secret only in memory, poll pending startup, confirm exact session
    ID + bridge generation through authenticated status, exercise status/result/
    exact cancel, then disable only when `enabled_by_request=true` and exact
    ownership still matches. Also exercise operation-ID-only lost-response
    recovery after the five-minute secret expiry, require stopped readback, and
    prove a staged APK replacement cannot satisfy its committed size/SHA-256.

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
