# Rusty Kiosk 0.6.1

## Decision

Publish `0.6.1` as the first Rusty Kiosk binary release. The `v0.6.0` source
tag remains as an exact record of a failed pre-publication workflow attempt;
that run stopped before asset upload or GitHub Release creation. The tag is not
rewritten or presented as a shipped binary.

## Reliability correction

The failed workflow duplicated APK signer parsing before calling the owned
bundle-staging script. The runner-side pipeline did not return the certificate
line and rejected the run. The staging script already captures both output
streams, verifies each APK, requires a same-signer pair, and records the signer
in the bundle manifest. `0.6.1` removes only the weaker duplicate check and
retains the owned fail-closed verification.

## Release contract

- main app version `0.6.1`, version code `11`;
- same-signer main and setup-helper APKs;
- exact source commit and semantic version;
- SHA-256 and byte counts for both APKs, the AGPL license, and source pointer;
- no replacement of an existing release's assets.
