# Transparent user controls

Rusty Kiosk presents passthrough appearance, setup-helper readiness, Wi-Fi ADB,
Accessibility, the direct PC link, local APK installation, and Meta Home as
separate capabilities.
Each setting has an effective-state readback, requires an explicit action before
it changes, and has a visible off route.

The app catalogue also has one explicit per-app launch requirement: **Any**,
**Wi-Fi on**, or **Wi-Fi off**. It is not a passive tag and applies equally to
Normal and Kiosk launch. Rusty Kiosk only reads ordinary Wi-Fi. When unmet it
opens Android Wi-Fi settings, never toggles Wi-Fi or Wi-Fi ADB, and shows a
cancel route while the bounded launch is pending.

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
- **Accessibility Enabled / Disabled** is effective `AccessibilityManager`
  readback for Rusty Kiosk's exact service.
- **Direct link Off / Starting / Ready / Error** combines the wearer's persisted
  opt-in with the local listener's effective state and address.
- The persistent pairing code is masked whenever the panel is created or the
  code changes. **Show pairing code** reveals it locally until **Hide** or the
  next panel/code transition; typed host status never exports it.
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

## Revocation

- Press **Disable Accessibility**. When the helper is unavailable, the active
  service retains its Android `disableSelf()` recovery path.
- Press **Disable Wi-Fi ADB**. This does not change Accessibility.
- Press **Stop asking after restart** before disabling Wi-Fi ADB if both should
  stay off after the next boot.
- Press **Disable direct link** to stop PC access without changing ADB,
  Accessibility, or launches. Rotate the pairing code to invalidate a saved PC
  credential; rotation also disables the link until re-enabled.
- Revoke Rusty Kiosk's per-app installer permission in Android settings to stop
  future local install sessions.
- An authorized USB bootstrap may disable the link only when its exact
  operation ID, ephemeral session ID, and bridge generation still match and it
  originally enabled the listener. A pre-existing wearer-enabled listener is
  not cleanup-owned by that host run.
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
