# Utility Example Agent Notes

The [catalogue](README.md) indexes separate consumers. Its standalone autoboot
example builds in its own Android project; it is not a Kiosk release asset.
Follow the owning Quest, Manifold, and application contracts before
implementing any linked composition.

Do not copy transport or command authority into an example. Keep package,
signer, feature, marker, and build identities distinct. Never expand the main
Kiosk app, setup helper, or fixed-target Launcher to make an example work.
Keep private application semantics, configuration, signing material, device
identities, and raw evidence out of this public repository.

Describe host checks as host checks. A build, fixture, or static check does not
prove boot launch, wearer readiness, browser compatibility, command effect,
or cleanup on a headset. Route live device work through `$meta-quest-workflow`.
