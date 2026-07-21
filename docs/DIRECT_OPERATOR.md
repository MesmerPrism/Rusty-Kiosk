# Direct operator link

## Purpose

The direct link removes routine ADB from the already bounded Rusty Kiosk and
Meta Quest File Manager workflow. It is single-headset local control, not fleet
management. The wearer enables or disables it in Rusty Kiosk's **User controls**
and can rotate the on-headset pairing code at any time.

The setup helper remains a separate same-signer APK with no network permission.
It still owns only fixed secure-setting operations after one USB-C provision.

## Fixed surface

Schema `rusty.kiosk.direct_operator.v1` listens on TCP port `39873` and exposes:

- contract and status;
- Rusty Kiosk's existing typed command admission and matching result readback;
- the single validated tag document;
- list/upload/download/delete within an app-owned staging directory;
- Android PackageInstaller admission and matching install receipts.

It exposes no shell, Android component or intent input, package-name launch,
setup-helper endpoint, protected app data, arbitrary filesystem path, device
setting, or fan-out operation. Staging names are single bounded filenames.

## Authentication and readback

The headset generates a random 128-bit Crockford-base32 pairing code. Each
request supplies a unique 8–64 character replay ID, Unix timestamp, body
SHA-256, and HMAC-SHA-256 over the canonical method, request target, ID,
timestamp, and digest. Requests expire after 90 seconds and 512 accepted replay
IDs survive service restarts. Large uploads are written to a temporary file,
hashed, and activated only after the signed digest matches.

Authenticated responses include the request ID, response digest, and an HMAC
over `RESPONSE`, the request ID, HTTP status, and digest. The PC never treats an
unsigned or mismatched response as headset readback.

This provides authentication, integrity, expiry, and replay resistance. It does
not encrypt HTTP bodies. Use a trusted local network or a private Windows
hotspot. Transport encryption is a future protocol-version change, not an
implicit property of v1.

## Files and APKs

Direct files live only in Rusty Kiosk's app-owned `operator-staging` directory.
This gives the PC a bounded upload/list/download/delete route without granting a
general headset filesystem capability. Ordinary Meta Quest File Manager ADB
browsing remains available separately for shell-visible shared paths.

An install request names one to 32 already staged `.apk` parts. Rusty Kiosk
copies them into one Android `PackageInstaller` session and explicitly requires
user action. Receipts progress through staging, Android admission, wearer
confirmation, then installed or failed. The PC must not convert a pending
receipt into success. The wearer must separately allow Rusty Kiosk as an
installer through Android's visible per-app setting. That source grant persists,
but arbitrary first-time installs still require one wearer decision per package
session. A base APK and all of its selected split APKs share one session and one
decision.

Meta Quest File Manager therefore presents authorized PC ADB installation as
the default unattended and batch route. Direct PackageInstaller is the explicit
fallback for times when ADB is unavailable and someone is wearing the headset.

The direct route does not offer ADB-only flags such as downgrade, test-only,
grant-all-runtime-permissions, or silent first install.

## Bootstrap and revocation

One-time USB-C ADB is still the supported way to install the main/setup APK pair
and grant `WRITE_SECURE_SETTINGS` only to the setup helper. Afterward, the direct
link itself needs ordinary reachable Wi-Fi, not Wi-Fi ADB.

To revoke direct access, disable it in **User controls**. To invalidate a saved
PC credential, rotate the pairing code; rotation also disables the listener
until the wearer enables it again. Uninstalling Rusty Kiosk removes its pairing
state, staging files, and receipts.
