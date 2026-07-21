# Transparent user controls

Rusty Kiosk presents setup-helper readiness, Wi-Fi ADB, Accessibility, and Meta
Home as separate capabilities. Each setting has an effective-state readback,
requires an explicit action before it changes, and has a visible off route.

## Components

- The main Spatial APK is unprivileged and never declares
  `WRITE_SECURE_SETTINGS`.
- The dedicated setup-helper APK is non-launchable, has no network permission,
  and is signed with the same key as the main APK.
- The helper can receive `WRITE_SECURE_SETTINGS` once from an attended USB-C ADB
  session. It then accepts only the signature-protected fixed-operation enum.
- No terminal application, loopback ADB client, generic command service, or raw
  shell exists in the product workflow.

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
7. Optionally choose **Ask after every restart**. This preference is off by
   default and can be reversed at any time.

Provisioning launches the main app but enables neither Wi-Fi ADB nor
Accessibility. Once provisioned, the helper's authority normally survives a
reboot, so Rusty Kiosk can request Wi-Fi ADB later even if the transport is off.
If the wearer declines a Meta prompt, they can press **Request Wi-Fi ADB** again;
Horizon still decides whether and how to present approval.

## Status model

- **Setup Not installed / Needs USB-C setup / Ready** is based on package,
  same-signer permission, and helper grant checks.
- **Wireless Debugging On / Off** is read from Android's effective global
  setting after every resume and helper completion.
- **Request after restart On / Off** is returned by the helper's private
  preference; it does not claim that Meta approved the transport.
- **Accessibility Enabled / Disabled** is effective `AccessibilityManager`
  readback for Rusty Kiosk's exact service.
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
- Uninstall the setup helper to remove the in-headset settings route. Browsing,
  tagging, normal launch, and an already running main app remain ordinary app
  behavior.
- An authorized USB-C ADB session may explicitly revoke the helper grant or
  reinstall both APKs.
- Press **Exit to Meta Home** or use Home while Rusty Kiosk is visible. Both
  routes disarm pending kiosk guard state.

## Security and privacy

- No network listener, ADB key, pairing code, endpoint, token, shell output, or
  list of other enabled Accessibility services is stored by Rusty Kiosk.
- `WRITE_SECURE_SETTINGS` is broad Android authority even though this helper
  exposes only fixed operations. Install only trusted, reproducibly built APKs
  and keep both packages under the same signing identity.
- Wi-Fi ADB remains developer access, not a consumer kiosk feature or device
  management plane.
- Accessibility retrieves no UI content and performs no clicks, gestures,
  global actions, or Meta Home interception.
