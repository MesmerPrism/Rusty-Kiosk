# Direct operator link

## Purpose

The direct link removes routine ADB from the already bounded Rusty Kiosk and
QuestIonAble File Manager workflow. It is single-headset local control, not fleet
management. The wearer enables or disables it in Rusty Kiosk's **User controls**
and can rotate the on-headset pairing code at any time.

The setup helper remains a separate same-signer APK with no network permission.
It still owns only fixed secure-setting operations after one USB-C provision.

## Fixed surface

Schema `rusty.kiosk.direct_operator.v2` listens on TCP port `39873` and exposes:

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

An optional authorized-USB bootstrap uses host provider
`rusty.kiosk.host_operator.v4` and result schema
`rusty.kiosk.direct_usb_bootstrap.v2`. `direct-enable` takes one unique
operation ID in the ContentProvider `arg`. It starts the listener if necessary
and returns an honest `pending` or `confirmed` startup state plus channel,
package, endpoint, bridge generation, session ID, five-minute expiry,
`enabled_by_request`, and one standard-Base64 random 32-byte session secret.
The persistent pairing code is never returned.

The secret is issued once, concurrency/rate bounded, scoped to this fixed
Direct protocol, and tied to one Stable/Labs app-private bridge generation. An
ephemeral client adds `X-Rusty-Session-Id` and uses the decoded bytes as its
HMAC key. Authenticated `/v1/status` echoes the exact session ID and generation
for confirmation; the public contract remains credential-free. Expiry,
disablement, rotation, or generation substitution fails closed. Audit retains
only IDs and issue/use/revocation times. The app-private session store persists
its last observed wall time and rejects issuance after clock rollback, so the
rate window cannot be reset by moving the clock backwards.

Operation IDs are one-time within a durable app-private bootstrap-issuance epoch. A
fixed 4096-entry exact ledger never evicts on bridge-generation change. When it
is full, malformed, or bound to a mismatched epoch, new issuance fails closed;
clearing app data creates the next bootstrap-issuance epoch and is the only reset path.
The stored state has an exact private schema. Replay arrays initialize only when
the entire state is absent; a present missing, null, or wrong-type
`issued_operations` field rejects instead of becoming an empty ledger.

The host owns exact-serial ADB evidence. Raw `content call` stdout must feed a
redacted in-memory parser and must not be echoed, logged, or placed in generic
diagnostics. If `enabled_by_request=true`, cleanup may call `direct-disable`
with the same operation ID in `arg`, plus typed extras
`expected_bridge_generation` (long) and `session_id` (string). Kiosk disables
only when all three identity values still match and the tombstone proves that
bootstrap, rather than the wearer, enabled that generation.
Disable dispatch may remain `pending`; the host confirms cleanup only after
no-argument `direct-status` reports both `direct_enabled=false` and
`direct_running=false` on the new generation.

The five-minute HMAC secret and cleanup authority are separate. A non-secret,
24-hour bounded tombstone retains operation ID, session ID, bridge generation,
`enabled_by_request`, cleanup state, and timestamps, but never the secret.
`direct-disable` requires that bootstrap itself enabled the link and atomically
rechecks the current generation. If an enable or cleanup response is lost,
DUMP-only `direct-recover-disable` accepts the known original operation ID and
can only disable that owned generation or re-dispatch its pending STOP. It
returns no session ID, secret, or pairing code. The retry mapping follows the
post-disable generation until stopped readback consumes it or its bound expires.

Listener START/STOP intents carry their expected generation. The service ignores
crossed stale actions and persists its internal running generation.
`direct_running` remains the raw service-observed boolean, so disable readback
cannot report a false stop while the old listener is still closing. Completion
requires enabled state, running state, and the internal running generation to
converge on the current `bridge_generation`; the internal generation is not an
additional host wire field.

This provides authentication, integrity, expiry, and replay resistance. It does
not encrypt HTTP bodies. Use a trusted local network or a private Windows
hotspot. Transport encryption is a future protocol-version change, not an
implicit property of v2.

## Files and APKs

Direct files live only in Rusty Kiosk's app-owned `operator-staging` directory.
This gives the PC a bounded upload/list/download/delete route without granting a
general headset filesystem capability. Ordinary QuestIonAble File Manager ADB
browsing remains available separately for shell-visible shared paths.

An install request names one to 32 already staged `.apk` parts and commits each
name to an exact integer byte count and lowercase SHA-256. Rusty Kiosk opens
each staged source once, verifies count and digest while copying that same
handle into one Android `PackageInstaller` session, and explicitly requires
user action. Receipts progress through staging, Android admission, wearer
confirmation, then installed or failed. The PC must not convert a pending
receipt into success. The wearer must separately allow Rusty Kiosk as an
installer through Android's visible per-app setting. That source grant persists,
but arbitrary first-time installs still require one wearer decision per package
session. A base APK and all of its selected split APKs share one session and one
decision.
Concurrent replacement, size drift, digest drift, duplicate names, and unknown
install fields abandon the session and fail closed before commit.
An abandon call that returns normally is terminal. If it throws, only a
PackageInstaller readback proving that the session is absent permits terminal
`failed`; present or unavailable readback records incomplete
`cleanup-required`. The operator retries that cleanup by resending the same
install body with a fresh authenticated transport request ID. This retry cannot
stage or commit a second session under the original install request ID. The
private receipt stores the exact ordered commitment list and its canonical
SHA-256. Cleanup requires exact equality to the incoming body. The public v1
receipt remains unchanged. Existing private receipt bytes are classified as
absent, valid, or damaged; only absent can admit a new PackageInstaller session.

QuestIonAble File Manager therefore presents authorized PC ADB installation as
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

Typed commands use durable provider epoch + request ID state. The direct route
exposes read-only `/v1/kiosk/request-status` and exact queued-request
`/v1/kiosk/cancel`; status never creates work. Requests expire after two
minutes. Claimed, wearer-pending, and terminal operations cannot be cancelled,
and process restart reconciliation never replays a claimed mutation.
