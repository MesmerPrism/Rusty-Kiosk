# Rusty Kiosk 0.6.5

## Decision

Publish `0.6.5` as the signed updater and launcher-consolidation release over
`0.6.4`. The public APK bundle remains the same-signer Rusty Kiosk and setup
helper pair. The conventional launcher is built separately for the Meta Store
and Quest Private App Business channels.

## Scope

This release:

- advances Rusty Kiosk to version `0.6.5`, version code `15`;
- consolidates the minimal launcher and its separate Store and Business package
  identities onto the public source line;
- preserves the existing Rusty Kiosk runtime behavior, Accessibility component
  identity, signer, and setup-helper authority;
- provides the immutable signed artifact used to exercise an in-place
  `0.6.4` to `0.6.5` updater path.

The launcher remains a separate, conventional native 2D app. Its two release
identities share one implementation and launcher signer, carry no permissions,
and only open the fixed Rusty Kiosk package after its pinned public release
signer and normal front door validate.

## Release contract

- main app version `0.6.5`, version code `15`;
- setup helper version `0.5.0`, version code `5`;
- same-signer main and setup-helper APKs;
- exact source commit and semantic version;
- SHA-256 and byte counts for both APKs, the AGPL license, and source pointer;
- no replacement of an existing release's assets.
