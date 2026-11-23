# Quest status and audio example

This standalone Quest APK lets a browser read battery level, charging, and
session status. **Identify headset** plays a bundled five-second tone. The
wearer starts a 15-minute Bluetooth session on Quest and authorizes the browser
with the displayed code. The Quest app owns playback and reports the result of
the exact request. Its launcher, package, service, permissions and build are
independent of Rusty Kiosk and Launcher Lite.

## Build and try it

From the Rusty Kiosk checkout, use JDK 17, Android SDK 34 and the repository's
Gradle wrapper. The example uses Android 12 or later.

```powershell
.\gradlew.bat -p examples/quest-status-audio testDebugUnitTest lintDebug assembleDebug
```

The debug APK is under `examples/quest-status-audio/build/outputs/apk/debug/`.
Install it through your authorized developer workflow and open **Quest Status
and Audio** on the headset. Start a session and grant the requested Bluetooth
permissions. The ongoing notification provides a local Stop action when
notifications are permitted.

Use a browser that supports Web Bluetooth. For a desktop trial, serve the
`browser/` directory from loopback and open `http://127.0.0.1:8765`.

```powershell
python -m http.server 8765 --bind 127.0.0.1 --directory examples/quest-status-audio/browser
```

For a phone trial, serve the browser's static application files over HTTPS.
Keep the phone within Bluetooth range of the headset. The browser uses its
normal chooser and OS bond flow. The pairing code authorizes this app's
session after the encrypted link is available. Type the code displayed on
Quest, select the headset, and try
**Identify headset** and **Stop playback**. Closing the browser or losing Bluetooth
does not extend the Quest session. Stop the session on Quest to revoke its code
and stop playback.
[Chrome Web Bluetooth requirements](https://developer.chrome.com/docs/capabilities/bluetooth)

Open the **Facilitator guide** on the page for setup and reset steps. When the
page is hosted over HTTPS, its QR code opens the same page on another device.
The code contains the page URL without its query or fragment. Each browser
still pairs using the separate code shown on the headset.

## Behavior and authority

- The visible Quest activity starts the foreground session. It expires after
  15 minutes and requires a new wearer start after process death or reboot.
  The app uses ordinary Bluetooth runtime grants and a user-visible connected
  device foreground service. Media playback belongs to that app session.
- The browser writes one typed request over encrypted GATT. Status, Play,
  Stop, and read-only lookup are the complete command surface. Play always
  selects the bundled clip. Stop applies only to this app's active clip.
- Successful Bluetooth writes acknowledge delivery. The browser matches the
  app's result by request ID before displaying an outcome. The Quest session
  retains bounded effect records and refuses new effects if its record set is
  full. Reconnect can look up the original effect within that session.
- The pairing code stays in browser memory. The browser can retain the identity
  of one unresolved command in the current tab across reload. Cached PWA files
  are static. A cached page still requires the headset and its active session
  for live controls.
- A separate, encrypted read-only GATT characteristic reports battery level,
  charging state, and whole seconds remaining with the authenticated session
  identity. It is optional for compatibility with earlier example APKs. The
  browser labels the last confirmed reading and estimates the countdown from
  that reading. The headset remains authoritative for expiry.

The example's v1 command profile uses one small GATT write and one read. A write carries
version, operation and 16 bytes of pairing code or request identity. A read
carries version, operation, result, playback state and the exact 16-byte
identity. Successful pairing returns the current Quest session identity in
that field. The browser compares it on reconnect before looking up a retained
command. The 21-byte status read adds version, battery, charging, remaining
seconds, and the same session identity. See the native and browser protocol
tests for the numeric encoding.

## Verify and distribute

Run the Android build and unit tests above, then run `node --test "test/*.test.js"` in
`browser/` for the shared GATT pin, protocol, and simulated browser flows. Keep those
results separate from actual pairing and playback. On a physical test, confirm
the target mobile browser can connect to the HTTPS origin, hear the clip start
and stop while an immersive app retains XR focus, and repeat the connection
after explicitly starting a new session following reboot.

The debug APK is the developer-sideload example. A release-signed package with
the same narrow permission scope can be considered for a Meta test channel or
SideQuest after device qualification and applicable review. Launcher Lite
retains its separate Store build. See the [utility distribution guide](../../docs/UTILITY_DISTRIBUTION.md).
