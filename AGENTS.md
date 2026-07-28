# Rusty Kiosk Agent Notes

Rusty Kiosk is a public Meta Quest app family. The main app is a Meta Spatial
SDK example; the separate Rusty Kiosk Launcher is a minimal native 2D handoff
app for private Meta release channels. Keep both portable, small, and free of
private-project identities, assets, study flows, device serials, logs,
screenshots, signing material, or generated APKs.

Read `README.md`, `docs/ARCHITECTURE.md`, `docs/CLI.md`, `docs/USER_CONTROL.md`,
`docs/TAG_FILE.md`, `docs/PANEL_PREVIEW.md`, `docs/KIOSK_LAUNCHER.md`, and
`docs/VALIDATION.md` before changing behavior.

## Product invariants

- The app is a normal Spatial SDK application, not Android HOME, device owner,
  managed Shared Mode, or tamper-resistant lock task.
- Rusty Kiosk itself never runs under its foreground guard.
- Normal launch disarms the guard and may resume the selected app's existing
  task.
- Initial kiosk launch starts a fresh selected-app task and arms the guard for
  one explicit target component. Guard recovery resumes that resulting kiosk
  session instead of clearing it again.
- Both visible and typed kiosk launches create one target-scoped five-second
  handoff lease. It may ignore only trailing Rusty Kiosk self-events while the
  selected target is being launched; it never suppresses Meta Home or another
  package, and a genuine Rusty Kiosk resume still disarms immediately.
- The first two distinct Meta Home invocations recover that target. The third
  within five seconds disarms the guard and returns to Rusty Kiosk.
- Once Rusty Kiosk is visible, a normal Meta Home press can open Meta Home.
- Accessibility observes package/window transitions only. Do not add UI-tree
  reads, text/view lookup, clicks, gestures, global actions, force-stop, or
  hidden Accessibility activation.
- Wi-Fi ADB and Accessibility are separate, visible opt-ins. Rusty Kiosk must
  show their effective status and keep both reversible.
- The main Rusty Kiosk APK never declares `WRITE_SECURE_SETTINGS`. Only the
  separately installed, same-signer setup helper may receive that one-time
  USB-C-provisioned authority.
- The setup helper has no launcher UI, network permission, terminal, or generic
  command surface. It accepts only the reviewed fixed-operation enum, changes
  only Rusty Kiosk's exact Accessibility component, and preserves every other
  enabled Accessibility service.
- Accessibility disablement must retain the active service's `disableSelf()`
  route for recovery when the setup helper is absent.
- Request-after-restart is a visible, reversible opt-in. A Wi-Fi ADB request may
  require Meta's visible protected approval. Never automate or imply approval.
- The visible **Exit to Meta Home** action and a normal Meta Home press from
  Rusty Kiosk both disarm pending guard state.
- A name-only tag-file entry that does not match an installed launchable app
  remains visible and is labeled not installed.
- A fresh Rusty Kiosk task restores the last search text, active tag filter,
  and selected visible app. The wearer clears the filters through the empty
  search field or **All apps**; a tag removed entirely by a tag-file reload
  must not remain as a hidden filter, and details must not retain an app that
  is outside the filtered list.
- The browser preview is synthetic interactive design tooling, never a runtime
  WebView or installed-app authority.
- The production Quest surface is a native Android View tree registered through
  Spatial SDK's XML panel route. The native desktop preview remains the
  source-bound design projection; Quest is authoritative for spatial placement,
  compositor output, apparent size, pointer input, and keyboard behavior.
- Keep panel geometry, stable control tags, browser projection, native fixtures,
  and `references/rusty-kiosk-panel-contract.v1.json` synchronized.
- Editable Quest fields are native Android `EditText` views inside the
  production `LayoutXMLPanelRegistration`: focusable in touch mode, soft input
  enabled on focus, and an explicit bounded IME request on focus and click.
  The main manifest must retain both Quest virtual-keyboard feature declarations.
  Browser and native previews remain visual/design authorities; the headset is
  the keyboard-behavior authority.
- Wearer-equivalent automation uses the typed debug CLI, never display-coordinate
  touch injection. Its exported component exists only under `src/debug`, requires
  the sender-held `android.permission.DUMP`, accepts only the reviewed command
  vocabulary, and queues one app-private request at a time.
- The CLI adapts into the same activity handlers as the visible controls. It
  must not add raw shell, arbitrary intents, arbitrary package/component input,
  USB-C provisioning, protected-prompt approval, Accessibility gestures, or a
  release component.
- Installed release builds expose the same bounded vocabulary to an already
  authorized ADB shell through `RustyKioskOperatorProvider`. The provider is
  protected by caller-held `android.permission.DUMP`, supports `call()` only,
  admits one app-private request at a time, and returns only the matching
  Base64-encoded structured receipt. Provider v2 may additionally transfer only
  the fixed tag document as ordered, bounded Base64 chunks with total-size,
  SHA-256, schema, and atomic-activation checks. It never accepts shell commands,
  Android components, intent actions, endpoints, device paths, or new setup
  operations.
- Desktop operator tools must admit a request through that provider, launch the
  fixed Rusty Kiosk activity, then poll the matching receipt. They must not
  reconstruct guard state, catalogue matching, tag semantics, or setup-helper
  authority on the host.
- Exact watchdog-state tests use the separate debug-only, `DUMP`-protected
  guard-transition receiver. One command means one state-machine Home
  transition; it must never be described as physical Meta-button or visible
  Android HOME parity.
- `launcher` is one conventional native 2D Android implementation, not a
  Spatial SDK app. It has two closed release identities: the Store package and
  the distinct Quest Private App Business package required by Meta
  distribution rules. Both builds have one Activity, one exact package query,
  no declared permissions, and no service, provider, receiver, installer,
  Accessibility, updater, analytics, account, or background authority.
- The launcher obtains its pinned Rusty Kiosk signing certificate from the
  provenance-bound public release manifest under `launcher/trust/` and validates
  it before opening the target package's normal front door. Multiple current
  signers fail closed. A missing package, wrong signer, missing front door, or
  rejected launch remains visible as a bounded install/repair explanation.
- The launcher accepts no package, component, certificate, URL, or command
  from intents or remote input. Its target identity and official install links
  are compile-time release inputs.
- Both launcher release identities use the same launcher signing identity and
  must remain behaviorally identical. They and Rusty Kiosk remain separately
  installed packages with distinct signing identities. Meta Store and Business
  release channels own their respective launcher distribution; Rusty Kiosk
  owns all kiosk, setup, install, and update behavior.

Use `$meta-quest-workflow` before any headset, ADB, APK install/launch, logcat,
screenshot, or physical-button validation. Keep raw device evidence private.

## Checks

The complete repository gate is required on every pull request and push to
`main`. Tagged releases rerun it against the signed release pair and must create
new versioned assets rather than overwrite an existing release.

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\check_repo.ps1
```
