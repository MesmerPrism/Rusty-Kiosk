# Rusty Kiosk 0.6.2

## Decision

Publish `0.6.2` as the first Rusty Kiosk binary release. The `v0.6.0` and
`v0.6.1` source tags remain exact, asset-free records of pre-publication
workflow failures; neither tag is rewritten or presented as a shipped binary.

## Reliability correction

The `v0.6.1` runner successfully built and verified the APK pair, but
PowerShell represented the native `apksigner` output as non-string pipeline
objects. The exact-line parser therefore could not observe the otherwise
successful certificate output. The staging script now normalizes every native
output object to text, accepts the documented SHA-256 certificate label with
bounded formatting variation, requires an exact 32-byte digest, and includes
the verifier output in any future missing-digest failure.

## Release contract

- main app version `0.6.2`, version code `12`;
- same-signer main and setup-helper APKs;
- exact source commit and semantic version;
- SHA-256 and byte counts for both APKs, the AGPL license, and source pointer;
- no replacement of an existing release's assets.
