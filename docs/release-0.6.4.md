# Rusty Kiosk 0.6.4

## Decision

Publish `0.6.4` as a watchdog-recovery reliability patch over the first
published `0.6.3` bundle. The earlier `v0.6.0`, `v0.6.1`, and `v0.6.2` source
tags remain exact, asset-free records of pre-publication workflow failures.

## Reliability correction

A successful Android launch request is now treated as provisional rather than
as proof that the selected target regained focus. The watchdog waits for an
observed target-package window event to confirm recovery. If Meta shell emits
late window events first, the watchdog can issue a bounded burst of up to three
recovery requests with minimum spacing between them. Those retries do not add
false Home invocations or weaken the existing third-Home escape path.

## Authority and safety boundary

The patch changes only the app-owned guard decision engine and recovery
scheduling. It does not add permissions, Accessibility UI inspection, gestures,
global actions, force-stop behavior, hidden activation, setup-helper operations,
or host-supplied commands. The target window remains the effective-runtime
confirmation signal.

## Release contract

- main app version `0.6.4`, version code `14`;
- setup helper remains version `0.5.0`, version code `5`;
- same-signer main and setup-helper APKs;
- exact source commit and semantic version;
- SHA-256 and byte counts for both APKs, the AGPL license, and source pointer;
- no replacement of an existing release's assets.
