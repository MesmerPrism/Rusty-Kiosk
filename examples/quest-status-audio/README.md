# Quest status and audio example

This standalone Quest APK lets a browser read battery level, charging and
session status. **Identify headset** plays a bundled five-second tone.
The wearer starts a 15-minute companion session on Quest. To pair a new
browser, select the headset over Bluetooth and confirm that the digits match
on both devices. The browser remembers the enrollment and reconnects over
the local network when the headset is available. The Quest app owns
playback and reports the result of each request.

## Build and try it

From the Rusty Kiosk checkout, use JDK 17, Android SDK 34 and the repository's
Gradle wrapper. The example uses Android 12 or later.

```powershell
.\gradlew.bat -p examples/quest-status-audio testDebugUnitTest lintDebug assembleDebug
```

The debug APK is under `examples/quest-status-audio/build/outputs/apk/debug/`.
Install it through your authorized developer workflow and open **Quest Status
and Audio** on the headset. Put the headset and browser on the same private
Wi-Fi network. Tap **Start companion session** and grant Nearby Devices if
prompted. The ongoing notification provides a local Stop action when
notifications are permitted.

For a desktop trial, use a browser with Web Bluetooth and serve the static
files from the development origin allowed by the APK.

```powershell
python -m http.server 8765 --bind 127.0.0.1 --directory examples/quest-status-audio/browser
```

Open `http://127.0.0.1:8765`. For an HTTPS trial, the example APK also
allows the `https://mesmerprism.github.io` origin. Choose **Connect headset**,
select the headset, and allow Local Network Access if the browser prompts.
Confirm the matching digits in both places. Try **Identify headset** and
**Stop playback**, then reload the page to check reconnect. When the saved
address changes and automatic rediscovery is unavailable, select the same
headset to reuse its enrollment. Use **Forget paired browsers** on Quest
to revoke all its saved enrollments.
[Chrome Local Network Access](https://developer.chrome.com/docs/modern-web-guidance/guides/security/local-network-access)

The page's **Facilitator guide** can display a QR link to the HTTPS page.
The QR contains only the page URL. Each browser enrolls on its own.

## Behavior and authority

- The wearer starts the foreground session. It expires after 15 minutes
  and requires a new wearer start after process death or reboot. Stop or
  expiry closes the listener and stops playback. Enrollment persists across
  sessions.
- Bluetooth reads public installation identity and the LAN address. The
  pairing exchange returns an encrypted enrollment ticket after both
  confirmations. Kiosk uses the shared pairing protocol and authenticated
  LAN request and reply format for status and audio. The
  example's HTTP status and results are visible on the local network, so
  use a trusted private network.
- Status, Play, Stop and read-only receipt lookup are the command surface.
  Play selects the bundled clip. Stop applies to the app's clip. An
  authenticated reply carries the exact request ID and playback outcome.
- The PWA stores enrollment per headset installation and remembers the current
  selection. To check an unresolved request after reconnect, it verifies the
  wearer-session identity and looks up the original ID without replaying it.
  The headset remains authoritative for expiry and status.

[Pairing client source pin](browser/vendor/quest-pairing.source.json) ·
[Retained license](browser/vendor/quest-pairing.LICENSE)

## Verify and distribute

Run the Android build and tests above, then run `node --test "test/*.test.js"`
from `browser/`. On a physical test, confirm first pairing, saved reconnect
after reload, reselecting the same headset, and revocation. Confirm that battery
and charging match the headset and that the clip starts and stops while another
immersive app retains focus. Record platform prompts and app-level results
separately.

The 0.3 debug example passed button confirmation, reload reconnect,
reconnect after stopping and restarting the headset session, and audible
playback on a Quest 3S with Windows Edge at the development origin. Edge
received the early Stop receipt while the wearer was in another immersive
app. The mobile HTTPS trial and headset-side revocation remain separate
hardware checks.

The debug APK is a standalone developer-sideload example. A release-signed
package with the same narrow permission and network scope can be considered
for a Meta test channel or SideQuest after device qualification and review.
Launcher Lite has its own Store build.
[Utility distribution guide](../../docs/UTILITY_DISTRIBUTION.md)
