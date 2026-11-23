# Distributing Quest utilities

Channel guidance checked 2026-11-23. Assess each APK or web build against its
actual features and permissions. Record publication, installation, runtime
effect and reboot behavior as separate results.

## How Rusty Kiosk is distributed

| Artifact | Delivery and scope |
| --- | --- |
| **Rusty Launcher Lite** | Published standalone Meta Horizon Store app. Its approved 2D baseline offers a local app library and normal launch without Developer Mode or a companion. The Window/Immersive update remains a separate candidate in the source record. Lite's closed manifest and visible launch behavior are the Store reference. |
| **Rusty Kiosk Labs launcher** | A distinct, link-only Meta Alpha app opens the expected, signer-verified Labs core. The core is installed separately. The Business handoff launcher has its own Quest Private App identity. |
| **Full Rusty Kiosk and setup helper** | Signed GitHub Release APK pair. QFM provisions it over authorized USB ADB. The setup helper alone receives a fixed settings grant. The tested guide pairs QFM `v0.5.0-alpha.14` with the Kiosk `v0.6.6-alpha.9` bundle it ships. Kiosk's separately published `v0.6.6-alpha.10` is a different release. |
| **Utility examples** | Documentation and separately built examples with their own package identities, permissions and validation. |

[Launcher Lite](LAUNCHER_LITE.md) · [Launcher variants and signing](KIOSK_LAUNCHER.md) ·
[Pinned Labs tester pair](LABS_TESTING.md) ·
[Kiosk GitHub releases](https://github.com/MesmerPrism/Rusty-Kiosk/releases) ·
[QFM provisioning guide](https://github.com/MesmerPrism/QuestIonAble-File-Manager/blob/f2d939350f8d76000fcd8469107f8c9b29a2740d/docs/rusty-kiosk-integration.md)

## Choose a channel

| Channel | Use it for |
| --- | --- |
| **Meta Horizon Store** | Public production builds using supported APIs and minimum permissions. Verify the merged manifest, including SDK contributions, and submit against applicable VRCs. |
| **Meta developer release channels** | Alpha, Beta, RC or link-shared testing of an app using supported capabilities. Useful for experimental UX and lifecycle testing ahead of Store readiness. Upload checks and policies still apply. Tester entitlement, Developer Mode, special Android grants and shell authority are distinct. |
| **SideQuest** | A candidate for a signed, user-visible APK with a clear install and normal-use path. Confirm listing, hosting and package layout with SideQuest. SideQuest supports basic in-headset APK installs on supported setups. A feature's ADB or permission provisioning remains a separate step. |
| **GitHub Releases or team-controlled delivery** | Developer tools, multi-APK bundles, host connectors and shell-dependent labs. Pin source, asset hashes, signer, package and firmware scope. Document setup, expected readback, Stop and recovery. Use restricted delivery for internal artifacts when the project and licences allow it. |
| **Meta for Work or Education** | Organization-admin deployment of private apps to enrolled devices. Business apps use a separate identity from Store apps. Use supported Shared Mode or Single App controls for kiosk enforcement. Admin assignment and the installed app's Android privileges remain distinct. |

[Meta distribution options](https://developers.meta.com/vr/policy/distribution-options/) ·
[Meta tester access](https://developers.meta.com/vr/resources/publish-release-channels-add-users/) ·
[Link-shared Meta builds](https://developers.meta.com/vr/essentials/metavr-share-builds/) ·
[SideQuest publisher portal](https://sidequestvr.com/dev-portal/apps/create) ·
[SideQuest in-headset installs](https://github.com/SideQuestVR/SideQuest/releases/tag/v1.3.0) ·
[Meta private apps](https://developers.meta.com/vr/resources/qfb-private-apps-dist/) ·
[Meta Shared Mode presets](https://work.meta.com/help/1963330597356341)

## Recommendations by feature

These are proposed routes. A candidate still needs review of the exact build
and its behavior for that channel.

| Capability | Recommended delivery |
| --- | --- |
| **App library, favorites, normal launch, read-only Wi-Fi status** | Store Lite is the working example. Use Meta preview for changes to its normal or immersive experience. Keep launch scoped to supported front doors. |
| **Phone pairing, app-owned commands and status** | Public HTTPS for the PWA. Use Meta preview for the Quest companion. A standalone companion is a plausible SideQuest candidate. For a Store candidate, confirm Meta's limited review criteria for the requested Bluetooth permissions and disclose the data flow. Document pairing, permissions, disconnect and revocation. |
| **App-owned background audio** | Meta preview first for a user-started player with visible lifecycle and app-level gain. Consider Store or SideQuest after testing the behavior and permissions. Keep the ADB-triggered prototype and system-wide media-volume controls in developer distribution. |
| **Standalone status and audio example** | Debug APK for developer sideload and static HTTPS for the browser. The separate APK uses ordinary Bluetooth grants and a wearer-started foreground service. Consider Meta preview or SideQuest after physical pairing and audio checks and review of the exact signed package. [Example](../examples/quest-status-audio/README.md) |
| **Wearer-aware app autoboot** | Start with the separate developer APK. Consider SideQuest or Meta preview after testing opt-in, reboot, foreground launch and cancellation. Store use needs review of boot and wake permissions as well as reliable supported behavior. |
| **Termux/Linux sidecar and desktop panel** | Developer distribution while setup depends on separately installed Termux components and user grants. Consider SideQuest for a standalone panel with clear dependency and onboarding instructions. Check redistribution rights for bundled dependencies. |
| **Kiosk foreground guard and menu closing** | Keep the current full Kiosk in the developer lane. Accessibility-based recovery requires explicit opt-in. Visibility and Shell modes have additional firmware and reserved-system-function constraints. Review SDK and channel terms before wider delivery, including SideQuest. Use supported managed controls when kiosk enforcement is needed. |
| **Wi-Fi ADB setup/recovery and privileged APK installation** | QFM with the signed setup pair or a team-controlled lab package. An ordinary launcher can go through Meta preview, as Kiosk demonstrates. The full setup separately requires developer authorization, its settings grant and any protected Horizon prompt. |
| **Shell recenter, device-wide awake/proximity, shell display mirroring, privileged camera capture** | Versioned GitHub or team-controlled developer tools, with the supported hardware, authority and cleanup steps documented. App-owned camera and wearer-consented platform capture are separate preview or Store candidates, subject to review. |
| **Live media, fleet or private study workflows** | Keep sensor, camera and recording permissions and privacy disclosures with the app that owns them. A standalone ordinary app can be tested in Meta preview. Use a restricted build for private assets or workflows. Review rights and data handling before wider distribution. |

Meta lists requests for `WRITE_SECURE_SETTINGS`, `DUMP`, `READ_LOGS`,
`QUERY_ALL_PACKAGES` and package-install permissions such as
`REQUEST_INSTALL_PACKAGES` among prohibited permissions. It also lists
`BIND_ACCESSIBILITY_SERVICE`. Android uses that permission as the system bind
guard on an Accessibility service. A component's caller protection and an app's
`uses-permission` request are distinct. Verify the exact merged manifest with
Meta and keep Accessibility-dependent Kiosk behavior outside Lite.

Boot, wake locks, Bluetooth, camera/microphone, notifications, Wi-Fi changes
and audio settings can require case-by-case permission review. Prefer app gain
over changing shared media volume. Android limits background activity launch
and some foreground service starts by OS and target SDK. For example, an app
targeting Android 15 or later cannot start a media-playback foreground service
directly from `BOOT_COMPLETED`. Meta's off-platform guidance also requires
respecting reserved SDK operations such as Home and system menus.

[Meta security VRC](https://developers.meta.com/vr/resources/vrc-quest-security-2/) ·
[Prohibited Android permissions](https://developers.meta.com/vr/resources/permissions-prohibited/) ·
[Permissions that require review](https://developers.meta.com/vr/resources/permissions-review-required/) ·
[Android service types and boot restrictions](https://developer.android.com/develop/background-work/services/fgs/service-types) ·
[Meta off-platform distribution](https://developers.meta.com/vr/policy/distribution-options/#off-platform-distribution-sideloading)

## Keep this guide current

When a Kiosk feature changes package, permissions, dependencies, boot or
background behavior, system API or release channel, update its row and the
linked Kiosk guide in the same change. Refresh an upstream reference when
adopting a new version or reviewing a release. Record the exact artifact,
package, signer, channel, Horizon OS, target SDK, approvals and strongest
observed result. Use the actual merged manifest and signed release metadata
to verify the distribution example.

[Lite candidate checks](LAUNCHER_LITE.md#candidate-assembly-and-verification) ·
[Repository validation](VALIDATION.md) ·
[Utility catalogue](../examples/README.md)
