# Rusty Kiosk trust anchor

`rusty-kiosk-v0.6.4-bundle-manifest.json` is the line-ending-normalized copy of
the public bundle manifest published with
[Rusty Kiosk v0.6.4](https://github.com/MesmerPrism/Rusty-Kiosk/releases/tag/v0.6.4).
The upstream asset is
[`bundle-manifest.json`](https://github.com/MesmerPrism/Rusty-Kiosk/releases/download/v0.6.4/bundle-manifest.json)
with SHA-256
`e0fe76729adb13c247a45f9f45e5990ce6610a2859818dfd135a2b8304715fc2`.

The launcher build reads its trusted signer from this manifest. Repository
validation binds the normalized fixture to the release version, source
revision, APK digest, signer digest, and upstream asset provenance.

Updating the trust anchor is a deliberate release operation: verify the new
public APK and bundle manifest, replace the fixture and provenance together,
then repeat the wrong-signer and trusted-launch headset cases.
