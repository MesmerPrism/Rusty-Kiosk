# Rusty Kiosk Labs tester setup

Rusty Kiosk Labs is a pre-release build for invited Meta Quest testers. The
current tested pair is:

- Rusty Kiosk Labs `v0.6.6-alpha.9` on the headset;
- QuestIonAble File Manager Labs `v0.5.0-alpha.14` on Windows.

The Meta Alpha app is a small launcher. It opens the separately installed
Rusty Kiosk Labs core, but it cannot install, update, or provision that core.
This keeps the Store-facing app narrow while leaving Kiosk's setup and
permissions visible to the wearer.

## Downloads

- [Join the Meta Alpha](https://www.meta.com/s/4SlXf1lVo)
- [Download the guided Windows Labs installer](https://github.com/MesmerPrism/QuestIonAble-File-Manager/releases/download/v0.5.0-alpha.14/QuestIonAbleFileManager-Labs-Setup.exe)
- [Inspect the exact Kiosk Labs release](https://github.com/MesmerPrism/Rusty-Kiosk/releases/tag/v0.6.6-alpha.9)
- [Inspect the exact File Manager Labs release](https://github.com/MesmerPrism/QuestIonAble-File-Manager/releases/tag/v0.5.0-alpha.14)

These exact-version links are intentional. Labs releases are immutable
prereleases and never replace the stable `latest` download.

## What you need

- a Meta Quest with Developer Mode enabled;
- a Windows 10 or 11 PC;
- a USB data cable for the first setup;
- current [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools);
- a Meta account eligible to accept the Alpha invite.

Developer Mode and the first USB debugging approval are required to install and
provision the Kiosk core. Normal use after setup does not require a terminal,
permanent Wi-Fi ADB, or a PC connection.

## Install the Kiosk core

1. Install Android SDK Platform Tools on the Windows PC.
2. Run `QuestIonAbleFileManager-Labs-Setup.exe`. The guided installer explains
   the project certificate before asking Windows to trust it and register the
   signed Labs app.
3. Connect the Quest with a USB data cable, put on the headset, and accept the
   USB debugging prompt. Select **Always allow from this computer** only on a PC
   you trust.
4. Open **QuestIonAble File Manager Labs**, select the ready `[USB]` headset,
   open **Rusty Kiosk**, confirm the **Labs** product channel, and choose
   **Install and provision (USB)**.
5. Open Rusty Kiosk Labs on the headset. Under **User controls**, confirm that
   setup is ready. Enable the **Accessibility soft guard** only if you want to
   test Kiosk launch and its Home-button return behavior. If Horizon blocks the
   service as a restricted setting, Rusty Kiosk opens its own app-details page;
   choose **Allow restricted settings** if offered, return, and try again. The
   guided USB provisioning route authorizes that gate for the exact Labs
   package, but deliberately does not enable Accessibility for you.

The File Manager installer embeds the exact signed Kiosk Alpha.9 bundle. You do
not need to download or select either Kiosk APK manually.

## Install the Meta Alpha launcher

1. Open the [Meta Alpha invite](https://www.meta.com/s/4SlXf1lVo) while signed in
   to the Meta account used on the headset and accept the invitation.
2. Allow a little time for the entitlement to propagate, then look for
   **Rusty Kiosk Lab Launcher** under **My Preview Apps** or the headset App
   Library. Refresh or reopen the Meta Horizon mobile app if it is not visible
   immediately.
3. Install the launcher and open it. When the trusted Labs core is present, it
   opens Rusty Kiosk. If the core is absent or has the wrong signer, it shows
   installation or repair guidance instead.

## Suggested first test

1. Search for an installed app, add a tag, filter by that tag, and remove it.
2. Focus a search or tag field and confirm the Quest keyboard appears.
3. Set an app's launch requirement to **Any**, **Wi-Fi on**, and **Wi-Fi off**.
   When the current state does not satisfy the requirement, confirm Kiosk opens
   Android's fixed Wi-Fi settings page. Kiosk never changes Wi-Fi itself.
4. Use **Normal launch** and confirm the app opens normally.
5. With the Accessibility soft guard enabled, use **Kiosk launch**. The first
   two separate Home presses should return to the guarded app; a third Home
   press within five seconds should return to Rusty Kiosk.
6. If an installed app publishes compatible read-only launch options, select an
   option and confirm that Kiosk launches it. Rusty Kiosk never accepts an
   arbitrary component, URI, flag, path, or free-form extra for this feature.

The per-app **Wi-Fi on/off requirement** is not Wi-Fi ADB. The requirement only
checks ordinary Wi-Fi state before an app launch and opens visible settings when
the state is wrong. Wi-Fi ADB is a separate, optional developer transport under
**User controls** and File Manager.

## Reporting a result

Please include:

- Quest model and Horizon OS version;
- the step that passed or failed;
- what you expected and what happened;
- whether the launch was Normal or Kiosk;
- whether the Accessibility soft guard was enabled;
- a screenshot or short recording when it is safe to share one.

Do not include pairing codes, device serials, account information, private
files, or other secrets. Report issues at
<https://github.com/MesmerPrism/Rusty-Kiosk/issues>.

## Pre-release limits

Rusty Kiosk is a soft launcher, not Android Home, device-owner lock task, or a
managed-device product. Meta Home and Horizon system UI remain available. The
Alpha is link-only preview distribution, not a searchable production listing.
Rusty Fleet, Morphovision, media-control examples, and other Rusty Morphospace
apps are separate products and are not installed by either launcher.
