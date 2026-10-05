# Transparent user controls

Rusty Kiosk presents passthrough appearance, setup-helper readiness, Wi-Fi ADB,
Accessibility, the direct PC link, local APK installation, and Meta Home as
separate capabilities.
Each setting has an effective-state readback, requires an explicit action before
it changes, and has a visible off route.

## Components

- The main Spatial APK is unprivileged and never declares
  `WRITE_SECURE_SETTINGS`.
- The dedicated setup-helper APK is non-launchable, has no network permission,
  and is signed with the same key as the main APK.
- The helper can receive `WRITE_SECURE_SETTINGS` once from an attended USB-C ADB
  session. It then accepts only the signature-protected fixed-operation enum.
- No terminal application, loopback ADB client, generic command service, or raw
  shell exists in the product workflow.
- The main APK's optional network listener accepts only the documented direct
  protocol. It is off by default and does not broaden the setup helper.

## New-headset setup

1. Enable Meta developer mode and USB debugging outside Rusty Kiosk.
2. Connect the headset to the host with USB-C and accept the headset's USB
   debugging trust prompt.
3. Build and provision both same-signer APKs with an explicit serial:

   ```powershell
   pwsh -NoProfile -ExecutionPolicy Bypass `
     -File .\tools\Provision-RustyKiosk.ps1 `
     -Serial <quest-serial>
   ```

4. Open **User controls**. **Setup: Ready** proves that the helper is installed,
   same-signer authorized, and holds the one-time provisioned settings grant.
5. Press **Request Wi-Fi ADB** only if wireless debugging is wanted. Horizon may
   show a protected Meta prompt; the wearer must approve or decline it.
6. Press **Enable Accessibility** only if soft-kiosk launch is wanted.
7. Press **Enable direct link** only if the local Windows operator is wanted.
   Enter the displayed address and pairing code on the PC. Wi-Fi ADB is not
   required for this connection.
8. If direct APK installation is wanted, press **Allow local APK installs** and
   respond to Android's visible per-app setting. Android still asks the wearer
   to confirm or cancel each install session.
9. Optionally choose **Ask after every restart**. This preference is off by
   default and can be reversed at any time.

Provisioning launches the main app but enables neither Wi-Fi ADB nor
Accessibility. Once provisioned, the helper's authority normally survives a
reboot, so Rusty Kiosk can request Wi-Fi ADB later even if the transport is off.
If the wearer declines a Meta prompt, they can press **Request Wi-Fi ADB** again;
Horizon still decides whether and how to present approval.

## Status model

- **Passthrough Natural / Contour LUT / Unavailable** combines Spatial SDK
  effective-state readback with the retained LUT application state. Natural is
  the persisted default. Contour LUT uses color bands to emphasize contours;
  it is not neighborhood edge detection.
- **Setup Not installed / Needs USB-C setup / Ready** is based on package,
  same-signer permission, and helper grant checks.
- **Wireless Debugging On / Off** is read from Android's effective global
  setting after every resume and helper completion.
- **Request after restart On / Off** is returned by the helper's private
  preference; it does not claim that Meta approved the transport.
- **Last boot request** appears in the status message after **Refresh setup
  status**, and is also refreshed on app resume. The helper retains only its
  latest real `BOOT_COMPLETED` observation: Android boot count (or unknown),
  elapsed time at delivery and at deferred dispatch, request outcome, connected
  Wi-Fi observation, and independent `adb_enabled` / `adb_wifi_enabled` setting
  readback. Unknown readback stays unknown. Outcomes include `waiting_for_wifi`,
  `opted_out`, `no_authority`, `requested`, `failed`, `expired`, `cancelled`, or
  `network_unavailable`. Manual operations preserve completed boot evidence;
  revocation cancels and updates a pending observation. An older helper reports
  no receipt; that is not evidence that
  a boot receiver ran.
- **Accessibility Enabled / Disabled** is effective `AccessibilityManager`
  readback for Rusty Kiosk's exact service.
- **Direct link Off / Starting / Ready / Error** combines the wearer's persisted
  opt-in with the local listener's effective state and address.
- **Local APK installer needs permission / wearer allowed** is Android
  `canRequestPackageInstalls()` readback, not an install-success claim.
- **Meta Home Available** reflects the normal Home path and explicit exit.

## Fixed interface

The helper accepts only:

- `status`;
- `request_wifi_adb` / `disable_wifi_adb`;
- `enable_accessibility` / `disable_accessibility`;
- `enable_wifi_after_boot` / `disable_wifi_after_boot`.

No request contains a user-supplied command, component, package, path, endpoint,
or argument. Accessibility mutations add or remove only Rusty Kiosk's exact
component while preserving all other enabled services.

The restart preference requests wireless debugging; it does not recreate
privileged shell authority or silently approve Horizon's protected prompt.
`requested` and a setting readback of On are not listener or connection proof.
Accept reboot recovery only after an already authorized external ADB client
connects to the rebooted headset without a host re-enable command. If Horizon
asks for approval, the wearer must provide it visibly; retain that limitation
separately from the boot delivery receipt.

Boot delivery can precede Wi-Fi association. The helper therefore waits for
Android to assign a connected infrastructure Wi-Fi network to one one-shot
job, then rechecks the preference and provisioning grant before requesting.
It opens no connection and does not poll or retry. A ten-minute window bounds
the request; Android may dispatch the expiry callback later under Doze, but
that late callback cannot enable ADB. A lost network at dispatch is recorded
without requesting. A trusted network may let Horizon restore its approved
transport; an untrusted network may still need visible wearer approval.

## Revocation

- Press **Disable Accessibility**. When the helper is unavailable, the active
  service retains its Android `disableSelf()` recovery path.
- Press **Disable Wi-Fi ADB**. This cancels the current pending boot request
  without changing Accessibility or the preference for the next boot.
- Press **Stop asking after restart** before disabling Wi-Fi ADB if both should
  stay off after the next boot.
- Press **Disable direct link** to stop PC access without changing ADB,
  Accessibility, or launches. Rotate the pairing code to invalidate a saved PC
  credential; rotation also disables the link until re-enabled.
- Revoke Rusty Kiosk's per-app installer permission in Android settings to stop
  future local install sessions.
- Uninstall the setup helper to remove the in-headset settings route. Browsing,
  tagging, normal launch, and an already running main app remain ordinary app
  behavior.
- An authorized USB-C ADB session may explicitly revoke the helper grant or
  reinstall both APKs.
- Press **Exit to Meta Home** or use Home while Rusty Kiosk is visible. Both
  routes disarm pending kiosk guard state.

## Security and privacy

- Rusty Kiosk stores only its last catalogue search, tag filter, and selected
  app key, generated pairing code, direct-link opt-in, bounded replay IDs,
  app-owned staging files, and install receipts. It stores no ADB key, shell
  output, or list of other enabled Accessibility services.
- Direct v1 authenticates and integrity-checks requests and responses but does
  not encrypt HTTP bodies. Use a trusted local network or private hotspot.
- `WRITE_SECURE_SETTINGS` is broad Android authority even though this helper
  exposes only fixed operations. Install only trusted, reproducibly built APKs
  and keep both packages under the same signing identity.
- Wi-Fi ADB remains developer access, not a consumer kiosk feature or device
  management plane.
- Accessibility retrieves no UI content and performs no clicks, gestures,
  global actions, or Meta Home interception.
