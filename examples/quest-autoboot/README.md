# Standalone Quest autoboot example

This is a separate, ordinary Android 2D example APK. It is not built into any
Rusty Kiosk product or launcher release. A fresh install has autostart **off**
and no selected app. The visible panel and the typed ADB operator route use the
same `AutobootController` for status, catalogue, selection, enablement, and the
wearer preference. The operator selects a current catalogue `choice_id`; it
cannot submit a component or intent to launch.

On `BOOT_COMPLETED`, the receiver starts one bounded pending attempt. Each retry
checks boot completion, the selected component's current exported `MAIN` front
door and category, and the monotonic four-minute deadline. With **Wait until
worn** enabled, it also requires an interactive display and Quest's observed
`sys.hmt.mounted=1`. This firmware property is an observation, not a portable
wearer API or XR readiness guarantee. Disabling autostart cancels the pending
alarm. The selected choice and wearer policy are bound at boot; changing the
choice cancels that attempt, while a wearer-policy change applies at the next
boot. Missing or changed targets stop without another selection or fallback.
The example records `launch-requested` when it calls Android `startActivity`;
that does not prove the target became foreground or ready. Android background
launch acceptance still depends on the installed OS.
The boot and retry receivers are non-exported; Android's system boot broadcast
still reaches the manifest receiver. This has not yet been verified on Quest.

The only manifest permissions are `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK`.
There is no Wi-Fi ADB, secure-settings grant, network listener, Accessibility,
device-owner, or app-management authority. Its package and preferences are
separate from the Kiosk main app, setup helper, and fixed-target Launcher.
If Kiosk's setup helper is also installed, its optional boot Wi-Fi ADB request
runs independently. This example neither waits for that request nor approves
any Meta prompt.

## Host build and tests

From the Rusty Kiosk repository root, with Android SDK and JDK 17 configured:

```powershell
.\gradlew.bat -p examples/quest-autoboot testDebugUnitTest lintDebug assembleDebug
```

The resulting APK is ignored at
`examples/quest-autoboot/build/outputs/apk/debug/QuestAutobootExample-debug.apk`.
Host tests cover disabled default decision, exact target absence, wearer and
boot gates, deadline, and choice-ID binding. A build or host test does not prove
Quest boot dispatch, firmware wearer observation, foreground launch, or XR
readiness.

## Typed operator route

After an authorized install and ADB connection, the provider is accessible
only to a caller holding Android `DUMP` (normally ADB shell). Use an explicit
serial and the wrapper:

```powershell
pwsh -NoProfile -File examples/quest-autoboot/Invoke-Operator.ps1 -Serial <serial> -Command status
pwsh -NoProfile -File examples/quest-autoboot/Invoke-Operator.ps1 -Serial <serial> -Command catalog
pwsh -NoProfile -File examples/quest-autoboot/Invoke-Operator.ps1 -Serial <serial> -Command select -ChoiceId <catalog-choice-id>
pwsh -NoProfile -File examples/quest-autoboot/Invoke-Operator.ps1 -Serial <serial> -Command wait -Value on
pwsh -NoProfile -File examples/quest-autoboot/Invoke-Operator.ps1 -Serial <serial> -Command enable -Value on
```

`enable -Value off` is the immediate cancellation route. `status` reports
selected availability, pending age and the last launch **request** outcome.
The provider supports only `ContentProvider.call()` methods `status`,
`catalog`, `select`, `enable`, and `wait`; it accepts no shell command, target
component, arbitrary intent, path, endpoint, or boot trigger. The host wrapper
requires a matching structured Bundle rather than trusting the ADB process
exit code alone.

## Provenance and adoption

This implementation follows the public [boot and wearer integration record](../boot-and-wearer.md).
It was written as a new standalone example with its own identity, state, and
operator protocol. Private prototype package identity, signing material,
target migration, and application policy are excluded. An integrating app may
reuse the pattern after reviewing its own boot permissions, target identity,
deadline, device behavior, and app-side effective-state evidence. Reusable
Android observation/dispatch should move to Rusty Quest only after a neutral
harness or second consumer establishes a stable boundary.
