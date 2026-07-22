# Rusty Kiosk 0.6.3

## Decision

Publish `0.6.3` as the first Rusty Kiosk binary release. The `v0.6.0`,
`v0.6.1`, and `v0.6.2` source tags remain exact, asset-free records of
pre-publication workflow failures; none is rewritten or presented as a shipped
binary.

## Reliability correction

GitHub's current Android build tools report a valid APK certificate as
`V2 Signer: certificate SHA-256 digest`, while the local SDK reports the same
field as `Signer #1 certificate SHA-256 digest`. The staging script now treats
the certificate field as the stable contract instead of the tool-specific
signer prefix. It normalizes every reported certificate SHA-256 value and
requires exactly one unique 32-byte signer digest. The release-pipeline test
now covers the ordinary local label, native-error output objects, and the
runner's V2 label.

## Release contract

- main app version `0.6.3`, version code `13`;
- same-signer main and setup-helper APKs;
- exact source commit and semantic version;
- SHA-256 and byte counts for both APKs, the AGPL license, and source pointer;
- no replacement of an existing release's assets.
